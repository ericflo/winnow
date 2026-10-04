package com.ericflo.winnow

import com.ericflo.winnow.data.LinkPreviewFetcher
import com.ericflo.winnow.data.LinkPreviewParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class LinkPreviewParserTest {
    @Test
    fun `open graph tags win, with relative images resolved`() {
        val html = """
            <html><head>
            <title>Fallback title</title>
            <meta property="og:title" content="Lake Tahoe &amp; the Sierra">
            <meta property='og:site_name' content='Wikipedia'>
            <meta name="description" content="A large freshwater lake.">
            <meta content="/static/tahoe.jpg" property="og:image" />
            </head></html>
        """.trimIndent()
        val parsed = LinkPreviewParser.parse(html, "https://en.wikipedia.org/wiki/Lake_Tahoe")
        assertEquals("Lake Tahoe & the Sierra", parsed.title)
        assertEquals("Wikipedia", parsed.site)
        assertEquals("A large freshwater lake.", parsed.description)
        assertEquals("https://en.wikipedia.org/static/tahoe.jpg", parsed.image)
    }

    @Test
    fun `falls back to the page title`() {
        val parsed = LinkPreviewParser.parse("<html><head><title>\n  Example   Domain \n</title></head></html>", "https://example.com/")
        assertEquals("Example Domain", parsed.title)
        assertNull(parsed.image)
        assertNull(parsed.site)
    }

    @Test
    fun `no title means no preview`() {
        assertNull(LinkPreviewParser.parse("<html><body>hi</body></html>", "https://example.com/").title)
    }

    @Test
    fun `links can't reach the phone's own network`() {
        val private = listOf("10.0.0.1", "192.168.1.1", "172.16.5.4", "127.0.0.1", "169.254.1.1", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1", "224.0.0.1")
        private.forEach { assertTrue(it, LinkPreviewFetcher.isPrivate(InetAddress.getByName(it))) }
        listOf("8.8.8.8", "100.128.0.1", "2606:4700:4700::1111").forEach { assertFalse(it, LinkPreviewFetcher.isPrivate(InetAddress.getByName(it))) }
        assertTrue(LinkPreviewFetcher.isPrivateLiteral("10.0.2.2"))
        assertTrue(LinkPreviewFetcher.isPrivateLiteral("::1"))
        assertFalse(LinkPreviewFetcher.isPrivateLiteral("8.8.8.8"))
        // Names aren't resolved here; the DNS filter handles them.
        assertFalse(LinkPreviewFetcher.isPrivateLiteral("en.wikipedia.org"))
    }
}
