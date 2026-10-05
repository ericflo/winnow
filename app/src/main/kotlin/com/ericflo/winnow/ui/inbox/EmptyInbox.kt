package com.ericflo.winnow.ui.inbox

import com.ericflo.winnow.data.ListHealth
import com.ericflo.winnow.data.StoreCounts

/**
 * Why the inbox has nothing to show, worked out from what's actually on the phone, so an empty
 * inbox never just says "No conversations yet" when there are texts Winnow isn't showing.
 */
sealed interface EmptyInbox {
    /** Winnow can't read texts: it isn't the SMS app ([isDefault] false), or Android hasn't let it. */
    data class NoAccess(val isDefault: Boolean) : EmptyInbox

    /** The phone has texts, but Winnow couldn't list any of them: [health] says which step failed. */
    data class NotListed(val counts: StoreCounts, val health: ListHealth?, val trashed: Int) : EmptyInbox

    /** Winnow can read the store, but its first listing since then hasn't finished. */
    data object Listing : EmptyInbox

    /** Every conversation is in Recently deleted. */
    data class AllDeleted(val trashed: Int) : EmptyInbox

    /** Every conversation is in Filtered or Archived. */
    data class Elsewhere(val filtered: Int, val archived: Int) : EmptyInbox

    /** There's nothing on the phone at all. */
    data object Nothing : EmptyInbox

    companion object {
        /**
         * Which it is. [listed] is every conversation Winnow listed, wherever it's filed;
         * [counts] what the store holds, counted directly (null if it couldn't be asked). Pure,
         * so it's unit-tested.
         */
        fun of(live: Boolean, isDefault: Boolean, counts: StoreCounts?, health: ListHealth?, listed: Int, filtered: Int, archived: Int, trashed: Int): EmptyInbox = when {
            !live -> NoAccess(isDefault)
            // Nothing can be said of what a listing found before one has finished.
            health == null -> Listing
            listed > 0 && filtered + archived > 0 -> Elsewhere(filtered, archived)
            counts == null || counts.unreadable -> NoAccess(isDefault)
            counts.texts > 0 -> NotListed(counts, health, trashed)
            trashed > 0 -> AllDeleted(trashed)
            else -> Nothing
        }

        /** A listing that's taken [elapsed] so far, for the problem report: where it is, and what it's done. */
        fun slowReport(counts: StoreCounts?, progress: com.ericflo.winnow.data.ListingProgress?, elapsed: Long): String = buildString {
            appendLine("Listing conversations has taken ${elapsed / 1000} s and hasn't finished.")
            counts?.let { appendLine("The message store has ${it.sms} texts, ${it.mms} picture messages and ${it.threads} conversations.") }
            if (progress == null) appendLine("No listing is under way: it may be waiting for the store to answer at all.")
            else {
                appendLine("Under way: ${progress.step}, for ${(System.currentTimeMillis() - progress.stepStartedAt) / 1000} s.")
                progress.done.forEach { (name, ms) -> appendLine("Done: $name in $ms ms") }
            }
        }

        /** What a listing that found nothing went through, for the problem report and the card. */
        fun report(counts: StoreCounts, health: ListHealth?): String = buildString {
            appendLine("The inbox listed no conversations, but the message store has ${counts.sms} texts, ${counts.mms} picture messages and ${counts.threads} conversations.")
            if (health == null) {
                appendLine("No listing has finished yet.")
            } else {
                appendLine(
                    "Last listing: ${health.threads} conversations in the store, ${health.threadsWithPeople} with people named, " +
                        "${health.withMessages} with a newest message, ${health.recovered} found from their messages, " +
                        "${health.withoutPeople} with no one found, ${health.listed} listed.",
                )
                health.failures.forEach { appendLine("Failed: $it") }
                health.steps.forEach { (name, ms) -> appendLine("Step: $name in $ms ms") }
            }
        }
    }
}
