package com.ericflo.winnow

import com.ericflo.winnow.classify.Pacer
import com.ericflo.winnow.classify.Pacer.Next
import com.ericflo.winnow.classify.Pacer.Trouble
import org.junit.Assert.assertEquals
import org.junit.Test

class PacerTest {
    @Test
    fun errorTextsSayWhatWentWrong() {
        assertEquals(Trouble.REFUSED, Pacer.troubleOf("Provider unavailable (systemone:typesafe: HTTP 401 bad key)"))
        assertEquals(Trouble.REFUSED, Pacer.troubleOf("x: HTTP 402 payment required"))
        assertEquals(Trouble.RATE_LIMITED, Pacer.troubleOf("x: HTTP 429 slow down"))
        assertEquals(Trouble.REJECTED, Pacer.troubleOf("x: HTTP 400 bad request"))
        assertEquals(Trouble.REJECTED, Pacer.troubleOf("x: HTTP 422 unprocessable"))
        assertEquals(Trouble.REFUSED, Pacer.troubleOf("x: HTTP 404 model not found"))
        assertEquals(Trouble.REFUSED, Pacer.troubleOf("x: HTTP 405 method not allowed"))
        assertEquals(Trouble.UNAVAILABLE, Pacer.troubleOf("x: HTTP 503 down"))
        assertEquals(Trouble.UNAVAILABLE, Pacer.troubleOf("x: timeout"))
        // It answered, unusably: set aside, not retried batch after batch.
        assertEquals(Trouble.REJECTED, Pacer.troubleOf("Provider unavailable (x: ${com.ericflo.winnow.classifier.message.MessageClassifier.UNUSABLE_ANSWER} response has no answers: {})"))
        assertEquals(Trouble.UNAVAILABLE, Pacer.troubleOf("x: unexpected end of stream on http://h/..."))
    }

    @Test
    fun aRateLimitBacksOffAndSlowsDownThenRecovers() {
        val p = Pacer()
        assertEquals(Next.Wait(5_000, Trouble.RATE_LIMITED), p.after(10, listOf(Trouble.RATE_LIMITED), now = 0))
        assertEquals(1, p.concurrency)
        assertEquals(Next.Wait(10_000, Trouble.RATE_LIMITED), p.after(0, listOf(Trouble.RATE_LIMITED), now = 5_000))
        assertEquals(Next.Wait(20_000, Trouble.RATE_LIMITED), p.after(0, listOf(Trouble.RATE_LIMITED), now = 15_000))
        // Answers again: no wait, and back to full speed a step at a time.
        assertEquals(Next.Go, p.after(24, emptyList(), now = 40_000))
        assertEquals(2, p.concurrency)
        assertEquals(Next.Go, p.after(24, emptyList(), now = 41_000))
        assertEquals(3, p.concurrency)
        assertEquals(Next.Wait(5_000, Trouble.RATE_LIMITED), p.after(3, listOf(Trouble.RATE_LIMITED), now = 42_000))
    }

    @Test
    fun waitsAreCappedAndAnOutageEndsOnlyAfterTenMinutesWithoutAnAnswer() {
        val p = Pacer()
        var now = 0L
        var last: Next = Next.Go
        repeat(12) {
            last = p.after(0, listOf(Trouble.UNAVAILABLE), now)
            if (last is Next.Wait) now += (last as Next.Wait).millis
        }
        assertEquals(Next.GiveUp(Trouble.UNAVAILABLE), last)
        assertEquals("an outage doesn't slow it down, only waits", 3, p.concurrency)
        val q = Pacer()
        val waits = (0 until 8).map { (q.after(0, listOf(Trouble.UNAVAILABLE), it * 1_000L) as Next.Wait).millis }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 120_000L, 120_000L, 120_000L), waits)
    }

    @Test
    fun anyAnswerRestartsTheTenMinutes() {
        val p = Pacer()
        p.after(0, listOf(Trouble.UNAVAILABLE), now = 0)
        p.after(1, listOf(Trouble.UNAVAILABLE), now = 9 * 60_000)
        // Ten minutes since the first trouble, but there was an answer at nine.
        assertEquals(true, p.after(0, listOf(Trouble.UNAVAILABLE), now = 10 * 60_000 + 1) is Next.Wait)
    }

    @Test
    fun aRefusedKeyEndsItAtOnceAndOneBadTextIsJustSkipped() {
        assertEquals(Next.GiveUp(Trouble.REFUSED), Pacer().after(0, listOf(Trouble.REFUSED), now = 0))
        assertEquals(Next.Go, Pacer().after(23, listOf(Trouble.REJECTED), now = 0))
    }

    @Test
    fun aServiceThatTurnsDownEveryTextEndsTheRunInsteadOfSendingTheWholeBacklog() {
        // A whole batch turned down, nothing answered: the request is wrong, not the texts.
        assertEquals(Next.GiveUp(Trouble.REJECTED), Pacer().after(0, List(24) { Trouble.REJECTED }, now = 0))
        // Small batches add up while nothing is answered.
        val p = Pacer()
        repeat(3) { assertEquals(Next.Go, p.after(0, List(3) { Trouble.REJECTED }, now = it * 1_000L)) }
        assertEquals(Next.GiveUp(Trouble.REJECTED), p.after(0, List(3) { Trouble.REJECTED }, now = 3_000))
    }

    @Test
    fun turnedDownTextsAmongAnswersAreJustSkipped() {
        val p = Pacer()
        // Most of a batch turned down, but one answer shows the request is fine.
        repeat(20) { assertEquals(Next.Go, p.after(1, List(20) { Trouble.REJECTED }, now = it * 1_000L)) }
        // And an answer starts the count again.
        val q = Pacer()
        q.after(0, List(9) { Trouble.REJECTED }, now = 0)
        q.after(5, emptyList(), now = 1_000)
        assertEquals(Next.Go, q.after(0, List(9) { Trouble.REJECTED }, now = 2_000))
    }
}
