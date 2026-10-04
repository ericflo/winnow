package com.ericflo.winnow.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
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

    fun parse(page: String, pageUrl: String): Parsed {
        // Only the <head>, and only so much of it: the tags live there, and a hostile page can't
        // make the regexes below crawl through half a megabyte.
        val headEnd = page.indexOf("</head>", ignoreCase = true).takeIf { it >= 0 } ?: page.length
        val html = page.substring(0, minOf(headEnd, MAX_HEAD_CHARS))
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

    private const val MAX_HEAD_CHARS = 64 * 1024

    private fun unescape(s: String): String = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
}

/**
 * Fetches and caches link previews. One GET for the page (at most [MAX_PAGE_BYTES]) and one for
 * its image (at most [MAX_IMAGE_BYTES]), no cookies, no referrer, at most [MAX_REDIRECTS]
 * redirects, and never to a private, loopback or link-local address: a link can't make the
 * phone probe its own network. Who gets previews at all is decided by the caller; see
 * ThreadViewModel. Definite misses are cached; a failure that might be temporary (offline, a
 * timeout) isn't, so the link is tried again later.
 */
class LinkPreviewFetcher(context: Context, client: OkHttpClient) {
    private val dir = File(context.cacheDir, "link-previews").apply { mkdirs() }
    private val client = client.newBuilder()
        .callTimeout(8, TimeUnit.SECONDS)
        // Followed by hand, so each hop is counted and checked.
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(PublicOnlyDns)
        // The address actually connected to, for every request: catches IP-literal hosts, which
        // skip Dns, and anything that resolved differently by the time of the connection.
        .addNetworkInterceptor { chain ->
            val address = chain.connection()?.route()?.socketAddress?.address
            if (address == null || isPrivate(address)) throw IOException("Refusing a private address")
            chain.proceed(chain.request())
        }
        .build()
    private val memory = ConcurrentHashMap<String, Optional<LinkPreview>>()
    private val inFlight = ConcurrentHashMap<String, Deferred<LinkPreview?>>()
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Serializable
    private data class Entry(val fetchedAt: Long, val preview: LinkPreview? = null)

    /** A page's preview, a definite "none", or a failure worth retrying later. */
    private sealed interface Result {
        data class Found(val preview: LinkPreview) : Result
        data object None : Result
        data object Transient : Result
    }

    suspend fun get(url: String): LinkPreview? {
        memory[url]?.let { return it.orElse(null) }
        // One fetch per link however many bubbles ask for it at once; different links don't wait on each other.
        val job = inFlight.computeIfAbsent(url) { scope.async { load(url) } }
        return try {
            job.await()
        } finally {
            inFlight.remove(url, job)
        }
    }

    private fun load(url: String): LinkPreview? {
        val key = sha1(url)
        val entryFile = File(dir, "$key.json")
        val cached = runCatching { json.decodeFromString(Entry.serializer(), entryFile.readText()) }.getOrNull()
            ?.takeIf { System.currentTimeMillis() - it.fetchedAt < MAX_AGE_MILLIS }
        if (cached != null) return cached.preview.also { memory[url] = Optional.ofNullable(it) }
        val preview = when (val result = fetch(url, key)) {
            is Result.Found -> result.preview
            Result.None -> null
            Result.Transient -> return null
        }
        runCatching { entryFile.writeText(json.encodeToString(Entry.serializer(), Entry(System.currentTimeMillis(), preview))) }
        memory[url] = Optional.ofNullable(preview)
        return preview
    }

    /** GETs [url], following up to [MAX_REDIRECTS] redirects; the response is open for [read]. */
    private fun <T> get(url: HttpUrl, accept: String, read: (okhttp3.Response) -> T): T? {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            // An IP-literal host skips Dns; refused here so not even a connection is attempted.
            if (isPrivateLiteral(current.host)) return null
            val request = Request.Builder().url(current).header("User-Agent", USER_AGENT).header("Accept", accept).build()
            client.newCall(request).execute().use { response ->
                if (!response.isRedirect) return read(response)
                current = response.header("Location")?.let { current.resolve(it) }?.takeIf { it.scheme == "https" || it.scheme == "http" } ?: return null
            }
        }
        return null
    }

    private fun fetch(url: String, key: String): Result = try {
        val pageUrl = url.toHttpUrlOrNull()?.takeIf { it.scheme == "https" || it.scheme == "http" } ?: return Result.None
        get(pageUrl, "text/html,application/xhtml+xml") { response ->
            val host = response.request.url.host.removePrefix("www.")
            val type = response.header("Content-Type").orEmpty().lowercase()
            when {
                !response.isSuccessful -> Result.None
                type.startsWith("image/") -> {
                    // A link straight to a picture previews as the picture.
                    saveImage(readAtMost(response.body.source(), MAX_IMAGE_BYTES), key)
                        ?.let { Result.Found(LinkPreview(url, response.request.url.pathSegments.lastOrNull()?.ifBlank { null } ?: host, host, imagePath = it)) }
                        ?: Result.None
                }
                type.contains("html") || type.isEmpty() -> {
                    val source = response.body.source()
                    source.request(MAX_PAGE_BYTES)
                    val html = source.buffer.snapshot(minOf(source.buffer.size, MAX_PAGE_BYTES).toInt()).utf8()
                    val parsed = LinkPreviewParser.parse(html, response.request.url.toString())
                    val title = parsed.title ?: return@get Result.None
                    // The real host, never the page's own og:site_name: a phishing page can call itself anything.
                    Result.Found(LinkPreview(url, title, host, parsed.description, parsed.image?.let { downloadImage(it, key) }))
                }
                else -> Result.None
            }
        } ?: Result.None
    } catch (e: IOException) {
        Log.i(TAG, "No preview for a link for now: ${e.javaClass.simpleName}")
        Result.Transient
    } catch (e: Exception) {
        Log.i(TAG, "No preview for a link: ${e.javaClass.simpleName}")
        Result.None
    }

    private fun downloadImage(imageUrl: String, key: String): String? = runCatching {
        val url = imageUrl.toHttpUrlOrNull()?.takeIf { it.scheme == "https" || it.scheme == "http" } ?: return null
        get(url, "image/*") { response ->
            if (!response.isSuccessful || response.header("Content-Type")?.startsWith("image/") != true) null
            else saveImage(readAtMost(response.body.source(), MAX_IMAGE_BYTES), key)
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

    /** System DNS, minus answers that point into the phone's own network. */
    private object PublicOnlyDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            Dns.SYSTEM.lookup(hostname).filterNot(::isPrivate).ifEmpty { throw UnknownHostException("$hostname has no public address") }
    }

    companion object {
        private const val TAG = "WinnowLinks"
        private const val MAX_PAGE_BYTES = 512L * 1024
        private const val MAX_IMAGE_BYTES = 2L * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60_000
        // A browser-like agent; some sites serve bots a page with no tags at all.
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

        /** A host written as an IP address that [isPrivate] refuses; names are left to the DNS filter. */
        fun isPrivateLiteral(host: String): Boolean {
            val literal = host.all { it.isDigit() || it == '.' } || ':' in host
            return literal && runCatching { isPrivate(InetAddress.getByName(host)) }.getOrDefault(true)
        }

        /** Loopback, private ranges, link-local, CGNAT (100.64/10), unique-local IPv6 (fc00::/7), multicast, unspecified. */
        fun isPrivate(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
            ) return true
            val b = address.address
            return when (address) {
                is Inet4Address -> (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
                is Inet6Address -> (b[0].toInt() and 0xFE) == 0xFC
                else -> true
            }
        }
    }
}
