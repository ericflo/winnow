package com.ericflo.winnow.classifier.http

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The single HTTP operation providers need. Tests substitute a lambda. */
fun interface HttpTransport {
    /** POSTs [jsonBody]. Returns any HTTP status; throws [IOException] on transport failure. */
    suspend fun postJson(url: String, headers: Map<String, String>, jsonBody: String): HttpResult
}

data class HttpResult(val status: Int, val body: String)

class OkHttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
) : HttpTransport {

    override suspend fun postJson(url: String, headers: Map<String, String>, jsonBody: String): HttpResult {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(jsonBody.toRequestBody(JSON))
            .build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use { HttpResult(it.code, it.body.string()) }
                    cont.resume(result)
                }
            })
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
