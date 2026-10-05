package com.ericflo.winnow

import com.ericflo.winnow.data.ListHealth
import com.ericflo.winnow.data.StoreCounts
import com.ericflo.winnow.ui.inbox.EmptyInbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyInboxTest {
    private val texts = StoreCounts(sms = 38_000, mms = 3_000, threads = 1_550)
    private val none = StoreCounts(sms = 0, mms = 0, threads = 0)

    private fun of(
        live: Boolean = true,
        isDefault: Boolean = true,
        counts: StoreCounts? = texts,
        listed: Int = 0,
        filtered: Int = 0,
        archived: Int = 0,
        trashed: Int = 0,
        health: ListHealth? = null,
    ) = EmptyInbox.of(live, isDefault, counts, health, listed, filtered, archived, trashed)

    @Test
    fun everythingArchivedSaysSoNotThatThereAreNoConversations() {
        assertEquals(EmptyInbox.Elsewhere(filtered = 0, archived = 1_550), of(listed = 1_550, archived = 1_550))
        assertEquals(EmptyInbox.Elsewhere(filtered = 87, archived = 1_463), of(listed = 1_550, filtered = 87, archived = 1_463))
    }

    @Test
    fun withoutTheSmsRoleTheTextsAreThereButUnreadable() {
        assertEquals(EmptyInbox.NoAccess(isDefault = false), of(live = false, isDefault = false, counts = null))
        // The role held but reading refused: a different fix.
        assertEquals(EmptyInbox.NoAccess(isDefault = true), of(live = true, counts = StoreCounts(-1, -1, -1)))
    }

    @Test
    fun textsInTheStoreThatWerentListedAreNeverAnEmptyPhone() {
        val health = ListHealth(0, threads = 1_550, threadsWithPeople = 0, withMessages = 1_550, recovered = 0, withoutPeople = 1_550, listed = 0, failures = listOf("who's in each conversation: SecurityException: denied"))
        val e = of(health = health, trashed = 2)
        assertEquals(EmptyInbox.NotListed(texts, health, 2), e)
        val report = EmptyInbox.report(texts, health)
        assertTrue(report, "38000 texts, 3000 picture messages and 1550 conversations" in report)
        assertTrue(report, "Failed: who's in each conversation: SecurityException: denied" in report)
    }

    @Test
    fun onlyAnEmptyStoreIsNothingAndDeletedOnesArePointedTo() {
        assertEquals(EmptyInbox.AllDeleted(12), of(counts = none, trashed = 12))
        assertEquals(EmptyInbox.Nothing, of(counts = none))
    }
}
