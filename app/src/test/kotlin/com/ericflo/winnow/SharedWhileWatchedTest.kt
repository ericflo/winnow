package com.ericflo.winnow

import com.ericflo.winnow.data.sharedWhileWatched
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedWhileWatchedTest {
    @Test
    fun aWatcherAfterEveryoneLeftGetsAFreshValueNotTheLastOne() = runTest {
        var store = "before"
        var reads = 0
        // Like the conversation list: each start reads the store as it is then, then waits for changes.
        val list = flow { reads++; emit(store); kotlinx.coroutines.awaitCancellation() }.sharedWhileWatched(backgroundScope)

        assertEquals("before", list.first())
        // Nobody watching for longer than the grace period: the reading stops.
        advanceTimeBy(10_000)
        runCurrent()
        store = "after"
        assertEquals("a later watcher sees the store as it is now", "after", list.first())
        assertEquals(2, reads)
    }

    @Test
    fun watchersTogetherShareOneReading() = runTest {
        var reads = 0
        val list = flow { reads++; emit(reads); kotlinx.coroutines.awaitCancellation() }.sharedWhileWatched(backgroundScope)
        val watching = backgroundScope.launch { list.take(1).toList() }
        val holder = backgroundScope.launch { list.collect { } }
        runCurrent()
        assertEquals(1, list.first())
        assertEquals(1, reads)
        holder.cancel()
        watching.cancel()
    }
}
