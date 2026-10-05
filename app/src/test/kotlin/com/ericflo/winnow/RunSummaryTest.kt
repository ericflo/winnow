package com.ericflo.winnow

import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.data.db.RunEntity
import com.ericflo.winnow.ui.runs.AnswerFilter
import com.ericflo.winnow.ui.runs.AnswerRow
import com.ericflo.winnow.ui.runs.RunState
import com.ericflo.winnow.ui.runs.RunSummary
import com.ericflo.winnow.ui.runs.headline
import com.ericflo.winnow.ui.runs.stateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSummaryTest {
    private fun row(
        said: Category,
        before: Category? = null,
        now: Category? = null,
        mine: Category? = null,
        previous: Category? = null,
        taught: Boolean = true,
        key: String = "sms:${said.ordinal}${before?.ordinal}${now?.ordinal}${mine?.ordinal}${previous?.ordinal}$taught",
    ) = AnswerRow(key, 1, "+15555550101", "Test", "text", said, null, 0.9, taught, before, 0.8, now, 0.8, mine, previous)

    @Test
    fun agreementIsCountedOnlyWhereThereIsSomethingToCompare() {
        val rows = listOf(
            row(Category.SPAM, before = Category.SPAM, now = Category.SPAM),
            row(Category.SPAM, before = Category.PERSONAL, now = Category.SPAM, mine = Category.SPAM),
            row(Category.MARKETING, before = Category.PERSONAL, now = Category.PERSONAL, mine = Category.REMINDER),
            // The model had no opinion (the text's gone), and the user never labeled it.
            row(Category.REMINDER),
        )
        val s = RunSummary.of(rows)
        assertEquals(4, s.answered)
        assertEquals(3 to 1, s.beforeCompared to s.beforeAgreed)
        assertEquals(3 to 2, s.nowCompared to s.nowAgreed)
        assertEquals(2 to 1, s.mineCompared to s.mineAgreed)
        assertEquals(0 to 0, s.askedBefore to s.changed)
        // Every category, in order, zeros included.
        assertEquals(Category.entries.toList(), s.byCategory.map { it.first })
        assertEquals(2, s.byCategory.single { it.first == Category.SPAM }.second)
        assertEquals(0, s.byCategory.single { it.first == Category.POLITICAL }.second)
    }

    @Test
    fun disagreementsAreTheCommonestFirst() {
        val rows = listOf(
            row(Category.SPAM, before = Category.PERSONAL, key = "a"),
            row(Category.SPAM, before = Category.PERSONAL, key = "b"),
            row(Category.MARKETING, before = Category.TRANSACTIONAL, key = "c"),
            row(Category.SPAM, before = Category.SPAM, key = "d"),
        )
        val d = RunSummary.of(rows).disagreements
        assertEquals(listOf(Triple(Category.PERSONAL, Category.SPAM, 2), Triple(Category.TRANSACTIONAL, Category.MARKETING, 1)), d)
    }

    @Test
    fun aRedoCountsWhatItAnsweredDifferently() {
        val rows = listOf(
            row(Category.SPAM, previous = Category.SPAM, key = "a"),
            row(Category.MARKETING, previous = Category.SPAM, key = "b"),
            row(Category.PERSONAL, key = "c"),
        )
        val s = RunSummary.of(rows)
        assertEquals(2 to 1, s.askedBefore to s.changed)
        assertEquals(listOf("b"), rows.filter(AnswerFilter.CHANGED::test).map { it.key })
    }

    @Test
    fun filtersPickWhatTheySay() {
        val agree = row(Category.SPAM, before = Category.SPAM, now = Category.SPAM, mine = Category.SPAM, key = "agree")
        val moved = row(Category.SPAM, before = Category.PERSONAL, now = Category.SPAM, key = "moved")
        val stuck = row(Category.SPAM, before = Category.PERSONAL, now = Category.PERSONAL, mine = Category.PERSONAL, key = "stuck")
        val unsure = row(Category.MARKETING, taught = false, key = "unsure")
        val rows = listOf(agree, moved, stuck, unsure)
        assertEquals(rows, rows.filter(AnswerFilter.ALL::test))
        assertEquals(listOf("moved", "stuck"), rows.filter(AnswerFilter.MODEL_DISAGREED::test).map { it.key })
        assertEquals(listOf("stuck"), rows.filter(AnswerFilter.STILL_DISAGREES::test).map { it.key })
        assertEquals(listOf("stuck"), rows.filter(AnswerFilter.YOU_DISAGREE::test).map { it.key })
        assertEquals(listOf("unsure"), rows.filter(AnswerFilter.UNSURE::test).map { it.key })
    }

    private fun run(finishedAt: Long? = 2, stopped: Boolean = false, kind: String = RunEntity.KIND_BACKLOG) = RunEntity(
        kind = kind, provider = "Jev (TypeSafe)", startedAt = 1, updatedAt = 2, finishedAt = finishedAt,
        planned = 120, conversations = 40, done = 80, labeled = 61, unsure = 9, stopped = stopped,
    )

    @Test
    fun aRunWithNoEndThatIsntRunningEndedWithWinnow() {
        assertEquals(RunState.RUNNING, stateOf(run(finishedAt = null), running = true))
        assertEquals(RunState.INTERRUPTED, stateOf(run(finishedAt = null), running = false))
        assertEquals(RunState.STOPPED, stateOf(run(stopped = true), running = false))
        assertEquals(RunState.FINISHED, stateOf(run(), running = false))
    }

    @Test
    fun headlinesSayWhatHappenedInTheServicesShortName() {
        assertEquals("Jev labeled 61 texts", headline(run(), RunState.FINISHED))
        assertEquals("Jev answered again about 70 texts", headline(run(kind = RunEntity.KIND_REDO), RunState.FINISHED))
        assertEquals("Stopped after 80 of 120 texts", headline(run(stopped = true), RunState.STOPPED))
        assertTrue(headline(run(finishedAt = null), RunState.INTERRUPTED).startsWith("Ended with Winnow after 80"))
        assertEquals("Jev is labeling: 80 of 120 texts", headline(run(finishedAt = null), RunState.RUNNING))
    }
}
