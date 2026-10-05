package com.ericflo.winnow.data.db

import androidx.room.RoomOpenDelegate
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every database an installed Winnow could have, upgraded to this version on real SQLite, then
 * checked the way Room checks it when the app opens it. A migration that leaves the schema
 * different from what Room expects makes the app crash on every launch after the update, so
 * this is the test for that, short of installing each release on a phone.
 */
class DatabaseMigrationTest {
    private val schemas = File("schemas/com.ericflo.winnow.data.db.WinnowDatabase")
    private val room = WinnowDatabase_Impl()
    private val delegate = WinnowDatabase_Impl::class.java.getDeclaredMethod("createOpenDelegate")
        .apply { isAccessible = true }.invoke(room) as RoomOpenDelegate
    private val latest = delegate.version
    private val migrations = room.createAutoMigrations(emptyMap())
    private val opened = mutableListOf<Pair<SQLiteConnection, File>>()

    @After
    fun close() = opened.forEach { (c, f) -> c.close(); f.delete() }

    private fun schema(version: Int): JsonObject =
        Json.parseToJsonElement(File(schemas, "$version.json").readText()).jsonObject.getValue("database").jsonObject

    /** A database as [version] of the app left it: its tables and indices, from Room's exported schema. */
    private fun createdAt(version: Int): SQLiteConnection {
        val file = File.createTempFile("winnow-$version-", ".db").also { it.delete() }
        val c = BundledSQLiteDriver().open(file.path)
        opened += c to file
        val db = schema(version)
        for (e in db.getValue("entities").jsonArray) {
            val entity = e.jsonObject
            val table = entity.getValue("tableName").jsonPrimitive.content
            c.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            entity["indices"]?.jsonArray?.forEach { c.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
        }
        db["views"]?.jsonArray?.forEach { v ->
            val view = v.jsonObject
            c.execSQL(view.getValue("createSql").jsonPrimitive.content.replace("\${VIEW_NAME}", view.getValue("viewName").jsonPrimitive.content))
        }
        db.getValue("setupQueries").jsonArray.forEach { c.execSQL(it.jsonPrimitive.content) }
        c.execSQL("PRAGMA user_version = $version")
        return c
    }

    /** What Room runs when the app opens a database left at [from]. */
    private fun upgrade(c: SQLiteConnection, from: Int) {
        var version = from
        while (version < latest) {
            val step = migrations.singleOrNull { it.startVersion == version } ?: error("no migration from $version")
            step.migrate(c)
            version = step.endVersion
        }
    }

    private fun rows(c: SQLiteConnection, sql: String): List<List<String?>> = c.prepare(sql).use { st ->
        buildList { while (st.step()) add(List(st.getColumnCount()) { i -> if (st.isNull(i)) null else st.getText(i) }) }
    }

    @Test
    fun theNewestSchemaIsExportedAndEveryVersionHasOne() {
        (1..latest).forEach { assertTrue("schema $it.json is missing", File(schemas, "$it.json").exists()) }
        assertEquals(latest, schema(latest).getValue("version").jsonPrimitive.content.toInt())
    }

    @Test
    fun everyEarlierVersionUpgradesToWhatRoomExpects() {
        for (version in 1 until latest) {
            val c = createdAt(version)
            upgrade(c, version)
            val result = delegate.onValidateSchema(c)
            assertTrue("upgrading from $version: ${result.expectedFoundMsg}", result.isValid)
            assertEquals("upgrading from $version broke a foreign key", emptyList<List<String?>>(), rows(c, "PRAGMA foreign_key_check"))
        }
    }

    @Test
    fun theMoveToSixCategoriesMergesScamsIntoSpamAndAsksAgainAboutAllButPoliticalLabels() {
        val c = createdAt(16)
        fun verdict(key: String, category: String?, userCategory: String?) = c.execSQL(
            "INSERT INTO verdicts (messageKey, threadId, address, category, confidence, action, sourceKind, sourceDetail, costUsd, decidedAt, userCategory) " +
                "VALUES ('$key', 1, '+15555550101', ${category?.let { "'$it'" }}, 0.9, 'FILTER', 'ON_DEVICE', '', 0, 0, ${userCategory?.let { "'$it'" }})",
        )
        verdict("sms:1", "phishing", "phishing")
        verdict("sms:2", "scam", null)
        verdict("sms:3", "marketing", "political")
        verdict("sms:4", "personal", "personal")
        verdict("sms:5", "spam", null)
        for ((i, label) in listOf("phishing", "scam", "political", "marketing").withIndex()) {
            c.execSQL("INSERT INTO corrections (threadId, buckets, label, featurizerVersion, createdAt, source) VALUES (1, '1', '$label', 1, $i, 'user')")
        }

        upgrade(c, 16)

        assertEquals(
            listOf(
                listOf("sms:1", "spam", "spam", "1"),
                listOf("sms:2", "spam", null, "0"),
                // Political labels stand as they were.
                listOf("sms:3", "marketing", "political", "0"),
                listOf("sms:4", "personal", "personal", "1"),
                listOf("sms:5", "spam", null, "0"),
            ),
            rows(c, "SELECT messageKey, category, userCategory, recheck FROM verdicts ORDER BY messageKey"),
        )
        assertEquals(listOf("spam", "spam", "political", "marketing"), rows(c, "SELECT label FROM corrections ORDER BY createdAt").map { it[0] })
        assertTrue(delegate.onValidateSchema(c).isValid)
    }

    @Test
    fun keepingRunsMarksEarlierServiceLabelsAsFromABacklogRunAndLeavesTheUsersAlone() {
        val c = createdAt(17)
        c.execSQL("INSERT INTO corrections (threadId, buckets, label, featurizerVersion, createdAt, messageKey, source) VALUES (1, '1', 'spam', 4, 1, 'sms:1', 'provider')")
        c.execSQL("INSERT INTO corrections (threadId, buckets, label, featurizerVersion, createdAt, messageKey, source) VALUES (1, '2', 'personal', 4, 2, 'sms:2', 'user')")
        c.execSQL(
            "INSERT INTO verdicts (messageKey, threadId, address, category, confidence, action, sourceKind, sourceDetail, costUsd, decidedAt) " +
                "VALUES ('sms:1', 1, '+15555550101', 'spam', 0.9, 'FILTER', 'provider', 'jev', 0, 0)",
        )

        upgrade(c, 17)

        assertEquals(listOf(listOf("provider", "0"), listOf("user", null)), rows(c, "SELECT source, runId FROM corrections ORDER BY createdAt"))
        // A verdict from before says nothing of the model's opinion or a run: those weren't kept.
        assertEquals(listOf(listOf(null, null, null, "0")), rows(c, "SELECT localCategory, fallbackReason, runId, promptExamples FROM verdicts"))
        assertTrue(delegate.onValidateSchema(c).isValid)
    }
}
