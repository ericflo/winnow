package com.ericflo.winnow

import android.app.ApplicationExitInfo
import com.ericflo.winnow.diagnostics.ProblemLog
import com.ericflo.winnow.diagnostics.ProblemLog.Kind
import com.ericflo.winnow.diagnostics.ProblemLog.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ProblemLogTest {
    @Test
    fun crashesAndFreezesAreProblemsButOrdinaryExitsArent() {
        assertEquals(Kind.CRASH, ProblemLog.kindOf(ApplicationExitInfo.REASON_CRASH))
        assertEquals(Kind.NOT_RESPONDING, ProblemLog.kindOf(ApplicationExitInfo.REASON_ANR))
        assertEquals(Kind.NATIVE_CRASH, ProblemLog.kindOf(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertNull("Android reclaiming memory", ProblemLog.kindOf(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertNull("the user's force stop", ProblemLog.kindOf(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertNull("an update", ProblemLog.kindOf(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
    }

    @Test
    fun theReportSaysWhatAndWhereThenEachProblemNewestFirst() {
        val problems = listOf(
            Problem(1_791_170_000_000, Kind.NOT_RESPONDING, "Android says: Input dispatching timed out"),
            Problem(1_791_160_000_000, Kind.CRASH, "Thread: main\njava.lang.IllegalStateException: boom\n\tat X.y(X.kt:1)\n"),
        )
        val report = ProblemLog.report(problems, "0.1.4 (104)", "Android 16 (API 36) · Google Pixel 9", now = 1_791_180_000_000, zone = ZoneId.of("UTC"))
        val lines = report.lines()
        assertEquals("Winnow problem report", lines[0])
        assertEquals("App: 0.1.4 (104) · Android 16 (API 36) · Google Pixel 9", lines[1])
        val freeze = report.indexOf("== Not responding")
        val crash = report.indexOf("== Crash")
        assertTrue("both are in it, newest first", freeze in 0 until crash)
        assertTrue(report.contains("java.lang.IllegalStateException: boom"))
    }

    @Test
    fun eachProblemSaysWhichVersionItHappenedIn() {
        val problems = listOf(
            Problem(1_791_170_000_000, Kind.CRASH, "Thread: main\nboom", version = "0.1.26 (126)"),
            Problem(1_791_160_000_000, Kind.NOT_RESPONDING, "Android says: timed out", version = "0.1.19 (119)"),
            Problem(1_791_150_000_000, Kind.CRASH, "from before versions were kept"),
        )
        val report = ProblemLog.report(problems, "0.1.26 (126)", "phone", now = 1_791_180_000_000, zone = ZoneId.of("UTC"))
        val headers = report.lines().filter { it.startsWith("== ") }
        assertTrue(headers[0], headers[0].endsWith("· this version =="))
        assertTrue(headers[1], headers[1].endsWith("· in 0.1.19 (119) =="))
        assertTrue("unknown is left unsaid, not guessed: ${headers[2]}", !headers[2].contains("version") && !headers[2].contains(" in "))
    }

    @Test
    fun theVersionIsKeptWithTheDetailsAndOldFilesStillRead() {
        val problem = Problem(5, Kind.CRASH, "Thread: main\n#version is not a header here", version = "0.1.27 (127)")
        assertEquals(problem, ProblemLog.decode(5, Kind.CRASH, ProblemLog.encode(problem)))
        assertEquals(Problem(5, Kind.CRASH, "Thread: main\nboom"), ProblemLog.decode(5, Kind.CRASH, "Thread: main\nboom"))
        assertEquals("no version: just the details", "x", ProblemLog.encode(Problem(1, Kind.CRASH, "x")))
    }

    @Test
    fun problemsAreFromAnEarlierVersionOnlyWhenAllOfThemKnownlyAre() {
        val old = Problem(1, Kind.CRASH, "", "0.1.19 (119)")
        val older = Problem(0, Kind.CRASH, "", "0.1.18 (118)")
        val now = Problem(2, Kind.CRASH, "", "0.1.26 (126)")
        val unknown = Problem(3, Kind.CRASH, "")
        assertEquals("0.1.19 (119)", ProblemLog.earlierVersion(listOf(old, older), "0.1.26 (126)"))
        assertNull("one is from this version", ProblemLog.earlierVersion(listOf(old, now), "0.1.26 (126)"))
        assertNull("one's version isn't known", ProblemLog.earlierVersion(listOf(old, unknown), "0.1.26 (126)"))
        assertNull(ProblemLog.earlierVersion(emptyList(), "0.1.26 (126)"))
    }

    @Test
    fun aHugeReportIsCutToWhatAShareCanCarry() {
        val huge = Problem(1, Kind.NOT_RESPONDING, "x".repeat(ProblemLog.MAX_REPORT_CHARS * 2))
        val report = ProblemLog.report(listOf(huge), "1", "phone", now = 2, zone = ZoneId.of("UTC"))
        assertTrue(report.length <= ProblemLog.MAX_REPORT_CHARS + 100)
        assertTrue(report.endsWith("[cut off: the rest didn't fit]"))
    }
}
