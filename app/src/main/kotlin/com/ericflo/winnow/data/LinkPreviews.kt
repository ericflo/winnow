package com.ericflo.winnow.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** What a link shows: the page's title, its site, and a picture if it has one. */
@Serializable
data class LinkPreview(
    val url: String,
    val title: String,
    val site: String,
    val description: String? = null,
    /** A downloaded copy of the page's image, in the app's cache. */
    val imagePath: String? = null,
)

/** The bits of a page's HTML a preview uses: Open Graph tags, falling back to <title>. */
object LinkPreviewParser {
    data class Parsed(val title: String?, val site: String?, val description: String?, val image: String?)

    private val META = Regex("""<meta\s+[^>]*>""", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""([a-zA-Z:_-]+)\s*=\s*("([^"]*)"|'([^']*)')""")
    private val TITLE = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun parse(html: String, pageUrl: String): Parsed {
        val meta = HashMap<String, String>()
        META.findAll(html).forEach { tag ->
            val attrs = ATTR.findAll(tag.value).associate { it.groupValues[1].lowercase() to (it.groupValues[3].ifEmpty { it.groupValues[4] }) }
            val key = (attrs["property"] ?: attrs["name"])?.lowercase() ?: return@forEach
            val content = attrs["content"]?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            meta.putIfAbsent(key, content)
        }
        val title = meta["og:title"] ?: meta["twitter:title"] ?: TITLE.find(html)?.groupValues?.get(1)?.let(::unescape)?.trim()?.replace(Regex("\\s+"), " ")
        val image = (meta["og:image:secure_url"] ?: meta["og:image"] ?: meta["twitter:image"])?.let { resolve(pageUrl, it) }
        return Parsed(
            title = title?.takeIf { it.isNotEmpty() }?.take(200),
            site = meta["og:site_name"]?.take(80),
            description = (meta["og:description"] ?: meta["description"])?.take(300),
            image = image,
        )
    }

    private fun resolve(base: String, link: String): String? = base.toHttpUrlOrNull()?.resolve(link)?.toString()

    private fun unescape(s: String): String = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
}

/**
 * Fetches and caches link previews. One GET for the page (at most [MAX_PAGE_BYTES]) and one for
 * its image (at most [MAX_IMAGE_BYTES]), no cookies, no referrer. Who gets previews at all is
 * decided by the caller; see ThreadViewModel. Failures are cached too, so a dead link isn't
 * retried every time the conversation opens.
 */
class LinkPreviewFetcher(context: Context, client: OkHttpClient) {
    private val dir = File(context.cacheDir, "link-previews").apply { mkdirs() }
    private val client = client.newBuilder()
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val memory = HashMap<String, LinkPreview?>()
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Entry(val fetchedAt: Long, val preview: LinkPreview? = null)

    suspend fun get(url: String): LinkPreview? = lock.withLock {
        if (memory.containsKey(url)) return memory[url]
        withContext(Dispatchers.IO) {
            val key = sha1(url)
            val entryFile = File(dir, "$key.json")
            val cached = runCatching { json.decodeFromString(Entry.serializer(), entryFile.readText()) }.getOrNull()
                ?.takeIf { System.currentTimeMillis() - it.fetchedAt < MAX_AGE_MILLIS }
            val preview = if (cached != null) cached.preview else fetch(url, key).also { fresh ->
                runCatching { entryFile.writeText(json.encodeToString(Entry.serializer(), Entry(System.currentTimeMillis(), fresh))) }
            }
            memory[url] = preview
            preview
        }
    }

    private fun fetch(url: String, key: String): LinkPreview? = try {
        val pageUrl = url.toHttpUrlOrNull()?.takeIf { it.scheme == "https" || it.scheme == "http" } ?: return null
        val request = Request.Builder().url(pageUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful) return null
            val type = response.header("Content-Type").orEmpty().lowercase()
            val finalUrl = response.request.url.toString()
            val site = response.request.url.host.removePrefix("www.")
            when {
                type.startsWith("image/") -> {
                    // A link straight to a picture previews as the picture.
                    saveImage(readAtMost(body.source(), MAX_IMAGE_BYTES), key)?.let {
                        LinkPreview(url, finalUrl.substringAfterLast('/').ifBlank { site }, site, imagePath = it)
                    }
                }
                type.contains("html") || type.isEmpty() -> {
                    val source = body.source()
                    source.request(MAX_PAGE_BYTES)
                    val html = source.buffer.snapshot(minOf(source.buffer.size, MAX_PAGE_BYTES).toInt()).utf8()
                    val parsed = LinkPreviewParser.parse(html, finalUrl)
                    val title = parsed.title ?: return null
                    LinkPreview(url, title, parsed.site ?: site, parsed.description, parsed.image?.let { downloadImage(it, key) })
                }
                else -> null
            }
        }
    } catch (e: Exception) {
        Log.i(TAG, "No preview for a link: ${e.javaClass.simpleName}")
        null
    }

    private fun downloadImage(imageUrl: String, key: String): String? = runCatching {
        val url = imageUrl.toHttpUrlOrNull()?.takeIf { it.scheme == "https" || it.scheme == "http" } ?: return null
        client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "image/*").build()).execute().use { response ->
            if (!response.isSuccessful || response.header("Content-Type")?.startsWith("image/") != true) return null
            saveImage(readAtMost(response.body.source(), MAX_IMAGE_BYTES), key)
        }
    }.getOrNull()

    /** The whole body if it's at most [max] bytes; null if it's bigger. */
    private fun readAtMost(source: okio.BufferedSource, max: Long): ByteArray? {
        source.request(max + 1)
        return if (source.buffer.size > max) null else source.buffer.readByteArray()
    }

    private fun saveImage(bytes: ByteArray?, key: String): String? {
        if (bytes == null || bytes.isEmpty()) return null
        val file = File(dir, "$key.img")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "WinnowLinks"
        const val MAX_PAGE_BYTES = 512L * 1024
        const val MAX_IMAGE_BYTES = 2L * 1024 * 1024
        const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60_000
        // A browser-like agent; some sites serve bots a page with no tags at all.
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    }
}
