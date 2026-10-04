package com.ericflo.winnow

import com.ericflo.winnow.data.LinkPreviewParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
