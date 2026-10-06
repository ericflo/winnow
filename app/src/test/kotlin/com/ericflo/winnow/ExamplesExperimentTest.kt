package com.ericflo.winnow

import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classify.ExamplesExperiment
import com.ericflo.winnow.classify.ExperimentSummary
import com.ericflo.winnow.classify.Trial
import com.ericflo.winnow.data.db.EvalItemEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ExamplesExperimentTest {
    private fun t(label: Category, plain: Category?, with: Category?, key: String) = Trial(key, 1, label, plain, 0.9, with, 0.9)

    @Test
    fun theSummarySaysHowExamplesMovedTheAnswers() {
        val s = ExperimentSummary.of(
            listOf(
                t(Category.SPAM, Category.SPAM, Category.SPAM, "a"),
                // Wrong plainly, right with examples: toward the user's label.
                t(Category.MARKETING, Category.PERSONAL, Category.MARKETING, "b"),
                // Right plainly, wrong with examples: away.
                t(Category.PERSONAL, Category.PERSONAL, Category.SPAM, "c"),
                // Wrong both ways, differently.
                t(Category.TRANSACTIONAL, Category.PERSONAL, Category.MARKETING, "d"),
                // Not answered both ways: left out.
                t(Category.TRANSACTIONAL, null, Category.TRANSACTIONAL, "e"),
            ),
        )
        assertEquals(ExperimentSummary(trials = 4, plainRight = 2, withRight = 2, changed = 3, toward = 1, away = 1), s)
    }

    @Test
    fun aKeptExperimentsTrialsComeBackFromItsTwoEvaluations() {
        val plain = listOf(EvalItemEntity(1, "sms:1", 5, "spam", "personal", 0.6), EvalItemEntity(1, "sms:2", 6, "personal", "personal", 0.9))
        val with = listOf(EvalItemEntity(2, "sms:1", 5, "spam", "spam", 0.8))
        val trials = ExamplesExperiment.trialsOf(plain, with)
        assertEquals(listOf(Trial("sms:1", 5, Category.SPAM, Category.PERSONAL, 0.6, Category.SPAM, 0.8)), trials)
    }
}
