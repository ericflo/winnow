package com.ericflo.winnow

import com.ericflo.winnow.classifier.message.Category
import com.ericflo.winnow.classify.Training
import com.ericflo.winnow.ui.train.Decision
import com.ericflo.winnow.ui.train.TrainState
import com.ericflo.winnow.ui.train.progressOf
import com.ericflo.winnow.ui.train.resume
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class TrainProgressTest {
    private fun candidate(threadId: Long, guess: Category) =
        Training.Candidate(threadId, listOf("+1555555010$threadId"), "Test $threadId", null, "text $threadId", guess, 0.6)

    private fun round(vararg guesses: Pair<Long, Category>) = Training.Round(guesses.map { (id, g) -> candidate(id, g) }, backlog = 40, labeled = 3)

    private val answered = TrainState.Reviewing(
        round(1L to Category.SPAM, 2L to Category.MARKETING, 3L to Category.PERSONAL, 4L to Category.TRANSACTIONAL),
        number = 2,
        decisions = mapOf(1L to Decision.Right, 2L to Decision.Is(Category.TRANSACTIONAL), 3L to Decision.Skip),
    )

    @Test
    fun answersComeBackAsGivenEvenWhenTheGuessesHaveMoved() {
        val saved = Json.decodeFromString(Training.Progress.serializer(), Json.encodeToString(Training.Progress.serializer(), progressOf(answered)))
        assertEquals(listOf(1L, 2L, 3L, 4L), saved.threadIds)
        // Rebuilt later, the model guesses differently for all of them.
        val rebuilt = round(1L to Category.PERSONAL, 2L to Category.TRANSACTIONAL, 3L to Category.SPAM, 4L to Category.MARKETING)
        val (r, decisions) = resume(rebuilt, saved)
        assertEquals(mapOf(1L to Decision.Right, 2L to Decision.Is(Category.TRANSACTIONAL), 3L to Decision.Skip), decisions)
        // Each answered one shows the guess it was answered against; the open one keeps its new guess.
        assertEquals(listOf(Category.SPAM, Category.MARKETING, Category.PERSONAL, Category.MARKETING), r.candidates.map { it.guess })
    }

    @Test
    fun aConversationLabeledElsewhereMeanwhileDropsOut() {
        val saved = progressOf(answered)
        // Conversation 1 was labeled from the inbox since: it's no longer in the rebuilt round.
        val (r, decisions) = resume(round(2L to Category.MARKETING, 3L to Category.PERSONAL, 4L to Category.TRANSACTIONAL), saved)
        assertEquals(listOf(2L, 3L, 4L), r.candidates.map { it.threadId })
        assertEquals(mapOf(2L to Decision.Is(Category.TRANSACTIONAL), 3L to Decision.Skip), decisions)
    }

    @Test
    fun anAnswerInACategoryThatNoLongerExistsIsLeftOpen() {
        val saved = Training.Progress(listOf(1L), listOf(Training.Progress.Answer(1L, guess = "phishing", answer = "spam")))
        val (r, decisions) = resume(round(1L to Category.SPAM), saved)
        assertEquals(emptyMap<Long, Decision>(), decisions)
        assertEquals(Category.SPAM, r.candidates.single().guess)
    }
}
