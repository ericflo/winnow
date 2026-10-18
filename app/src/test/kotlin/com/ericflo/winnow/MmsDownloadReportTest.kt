package com.ericflo.winnow

import com.ericflo.winnow.sms.MmsDownloadReport
import org.junit.Assert.*
import org.junit.Test

class MmsDownloadReportTest {
    @Test fun urlReportIdentifiesExactBytesWithoutExposingDownloadTokens() {
        val url = "http://user:password@mmsc.example:8080/secret-path?token=secret-token#secret-fragment"
        val report = MmsDownloadReport.url(url)
        for (secret in listOf("user", "password", "secret-path", "secret-token", "secret-fragment")) assertFalse(report.contains(secret))
        assertTrue(report.contains("http://mmsc.example:8080"))
        assertTrue(report.contains("fragment=true"))
        assertTrue(report.contains(MmsDownloadReport.fingerprint(url)))
        assertNotEquals(MmsDownloadReport.fingerprint(url), MmsDownloadReport.fingerprint(url + "="))
    }

    @Test fun urlEncodingIsReportedWithoutNormalization() {
        val encoded = "http://m/get?token=a%2Fb="
        val decoded = "http://m/get?token=a/b="
        assertTrue(MmsDownloadReport.url(encoded).contains("ends '='=true"))
        assertTrue(MmsDownloadReport.url(encoded).contains("percent escapes=1"))
        assertNotEquals(MmsDownloadReport.fingerprint(encoded), MmsDownloadReport.fingerprint(decoded))
        val malformed = MmsDownloadReport.url("http://m/private token\n")
        assertTrue(malformed.contains("whitespace=true"))
        assertFalse(malformed.contains("private token"))
    }

    @Test fun carrierHeadersNeverExposeThePhoneNumberOrAnUnrecognizedHeader() {
        val report = MmsDownloadReport.identityHeaders("x-up-calling-line-id:15555550123|X-VzW-MDN: 1##LINE1NOCOUNTRYCODE##|Secret:secret-value")
        assertEquals("x-up-calling-line-id=true, x-vzw-mdn=true", report)
        assertEquals("unavailable", MmsDownloadReport.identityHeaders(null))
        assertFalse(MmsDownloadReport.identityHeaders("").contains("true"))
    }

    @Test fun failureTimeIsNotMistakenForNotificationArrivalOrExpiry() {
        val now = 1_792_267_200L
        val old = MmsDownloadReport.timeline(now - 86400, now - 1, now - 3600, now)
        assertTrue(old.contains("predates this app installation/update"))
        assertTrue(old.contains("age seconds=86400"))
        assertTrue(old.contains("elapsed before request"))
        val fresh = MmsDownloadReport.timeline(now - 10, now + 3600, now - 3600, now)
        assertTrue(fresh.contains("received since this app installation/update"))
        assertTrue(fresh.contains("not elapsed at request"))
        val unknown = MmsDownloadReport.timeline(null, 0, now, now)
        assertTrue(unknown.contains("arrival relative to update unknown"))
        assertTrue(unknown.contains("expiry unknown"))
    }
}
