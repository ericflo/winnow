package com.ericflo.winnow

import com.ericflo.winnow.classifier.local.OnDeviceClassifier
import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classifier.message.InboundMessage
import com.ericflo.winnow.classify.PersonalModelStore
import com.ericflo.winnow.data.db.CorrectionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersonalModelStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun row(id: Long, label: String = "spam", buckets: String = "1,2,3", source: String = CorrectionEntity.SOURCE_USER) =
        CorrectionEntity(id = id, threadId = 1, buckets = buckets, label = label, featurizerVersion = 3, createdAt = 0, source = source)

    @Test
    fun theStampIsTheSameOnlyWhenNothingThatGoesIntoAFitChanged() {
        val rows = listOf(row(1), row(2, "personal", "4,5"), row(3, "reminder", "6"))
        val stamp = PersonalModelStore.stampOf(rows, install = 100)
        assertEquals("order doesn't matter", stamp, PersonalModelStore.stampOf(rows.reversed(), install = 100))
        assertNotEquals("a label changed", stamp, PersonalModelStore.stampOf(listOf(row(1, "marketing"), rows[1], rows[2]), install = 100))
        assertNotEquals("what it learned from changed", stamp, PersonalModelStore.stampOf(listOf(row(1, buckets = "1,2,9"), rows[1], rows[2]), install = 100))
        assertNotEquals("a label went", stamp, PersonalModelStore.stampOf(rows.take(2), install = 100))
        assertNotEquals("who gave it changed", stamp, PersonalModelStore.stampOf(listOf(row(1, source = CorrectionEntity.SOURCE_PROVIDER), rows[1], rows[2]), install = 100))
        assertNotEquals("the app was updated", stamp, PersonalModelStore.stampOf(rows, install = 101))
    }

    @Test
    fun aKeptFitComesBackOnlyUnderItsOwnStamp() {
        val base = OnDeviceClassifier()
        val fitted = base.learn(listOf(base.correction(InboundMessage("+12065550123", "Landlord: test the heater before Friday"), setOf(Category.REMINDER))!!))
        val store = PersonalModelStore(folder.root.resolve("personal-model.bin"), install = 100)
        assertNull("nothing kept yet", store.load(42))
        store.save(42, fitted.adjustments)
        assertEquals(fitted.adjustments.size, store.load(42)!!.size)
        assertNull("another stamp: fit again", store.load(43))
        folder.root.resolve("personal-model.bin").writeText("not a model")
        assertNull("unreadable: fit again", store.load(42))
    }
}
