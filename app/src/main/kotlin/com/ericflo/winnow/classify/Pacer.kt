package com.ericflo.winnow.classify

/**
 * How a backlog run (see [Bootstrap]) paces itself when the classifier service pushes back. A
 * real service rate-limits and a phone loses its connection in a lift, and neither should end a
 * run that waiting would finish: the run backs off (5 s, 10 s, … up to 2 minutes), sends fewer
 * requests at once while it's being limited and more again once answers flow, and gives up only
 * after [giveUpAfterMillis] without a single answer. What waiting can't fix (a refused key, an
 * account out of credit) ends it at once. Pure, so it's unit-tested.
 */
class Pacer(
    private val maxConcurrency: Int = 3,
    private val firstWaitMillis: Long = 5_000,
    private val maxWaitMillis: Long = 120_000,
    private val giveUpAfterMillis: Long = 10 * 60_000,
) {
    /** What went wrong with one request, from the provider's error text. */
    enum class Trouble {
        /** Too many requests (429): slow down. */
        RATE_LIMITED,

        /** No connection, a timeout, or the service's own error (5xx): wait and retry. */
        UNAVAILABLE,

        /** A refused key or no credit (401/402/403): waiting won't help. */
        REFUSED,

        /** The service rejected this one text (another 4xx): skip it, carry on. */
        REJECTED,
    }

    sealed interface Next {
        data object Go : Next

        /** Wait [millis] (because of [trouble]) before the next batch, which retries what failed. */
        data class Wait(val millis: Long, val trouble: Trouble) : Next

        data class GiveUp(val trouble: Trouble) : Next
    }

    /** Requests to send at once now. */
    var concurrency: Int = maxConcurrency
        private set

    private var wait = 0L
    private var troubleSince: Long? = null

    /**
     * After a batch: [answered] texts got an answer, [troubles] are what went wrong with the rest
     * (skipped ones included). Returns what to do before the next batch.
     */
    fun after(answered: Int, troubles: List<Trouble>, now: Long): Next {
        if (Trouble.REFUSED in troubles) return Next.GiveUp(Trouble.REFUSED)
        val worst = when {
            Trouble.RATE_LIMITED in troubles -> Trouble.RATE_LIMITED
            Trouble.UNAVAILABLE in troubles -> Trouble.UNAVAILABLE
            else -> null
        }
        if (answered > 0) troubleSince = null
        if (worst == null) {
            // Answers flow again: back toward full speed, one step a batch.
            wait = 0
            if (concurrency < maxConcurrency) concurrency++
            return Next.Go
        }
        if (worst == Trouble.RATE_LIMITED) concurrency = (concurrency / 2).coerceAtLeast(1)
        val since = troubleSince ?: now.also { troubleSince = it }
        if (answered == 0 && now - since >= giveUpAfterMillis) return Next.GiveUp(worst)
        wait = if (wait == 0L) firstWaitMillis else (wait * 2).coerceAtMost(maxWaitMillis)
        return Next.Wait(wait, worst)
    }

    companion object {
        /** What a failed request's error text says went wrong. */
        fun troubleOf(detail: String): Trouble = when {
            Regex("""HTTP 40[123]\b""").containsMatchIn(detail) -> Trouble.REFUSED
            Regex("""HTTP 429\b""").containsMatchIn(detail) -> Trouble.RATE_LIMITED
            Regex("""HTTP 4\d\d\b""").containsMatchIn(detail) -> Trouble.REJECTED
            else -> Trouble.UNAVAILABLE
        }
    }
}
