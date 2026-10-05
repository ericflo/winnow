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
    fun aHugeReportIsCutToWhatAShareCanCarry() {
        val huge = Problem(1, Kind.NOT_RESPONDING, "x".repeat(ProblemLog.MAX_REPORT_CHARS * 2))
        val report = ProblemLog.report(listOf(huge), "1", "phone", now = 2, zone = ZoneId.of("UTC"))
        assertTrue(report.length <= ProblemLog.MAX_REPORT_CHARS + 100)
        assertTrue(report.endsWith("[cut off: the rest didn't fit]"))
    }
}
