package com.nuvio.tv.data.floppy

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource

/** Why a Floppy request failed. Messages never carry a response body, token or header. */
sealed class FloppyApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unauthorized : FloppyApiError("Floppy rejected the credentials (HTTP 401)")
    class Forbidden : FloppyApiError("Floppy denied access (HTTP 403)")
    class NotFound : FloppyApiError("Floppy resource not found (HTTP 404)")
    class Conflict : FloppyApiError("Floppy reported a conflict (HTTP 409)")
    class RateLimited(val retryAfterSeconds: Long?) : FloppyApiError("Floppy rate limit reached (HTTP 429)")
    class Server(val status: Int) : FloppyApiError("Floppy server error (HTTP $status)")
    class Redirect(val status: Int) : FloppyApiError("Floppy answered with a redirect (HTTP $status)")
    class Network(cause: IOException) : FloppyApiError("Floppy could not be reached", cause)
    class InvalidResponse : FloppyApiError("Floppy returned an unreadable response")

    /** Any other 4xx, such as 400 for a malformed request. */
    class Rejected(val status: Int) : FloppyApiError("Floppy rejected the request (HTTP $status)")
}

enum class FloppyHttpMethod { GET, POST, DELETE }

/** [url] is absolute. [bearer] is only attached when the URL is on the configured server's origin. */
class FloppyHttpRequest(
    val method: FloppyHttpMethod,
    val url: String,
    val json: String? = null,
    val form: Map<String, String>? = null,
    val bearer: String? = null,
    val headers: Map<String, String> = emptyMap()
) {
    override fun toString(): String = "FloppyHttpRequest(method=$method)"
}

class FloppyHttpResponse(
    val status: Int,
    val body: String,
    val retryAfter: String? = null
) {
    val isSuccess: Boolean get() = status in 200..299

    /** Throws the classified [FloppyApiError] unless this is a 2xx. */
    fun requireSuccess(): FloppyHttpResponse {
        if (isSuccess) return this
        throw when (status) {
            401 -> FloppyApiError.Unauthorized()
            403 -> FloppyApiError.Forbidden()
            404 -> FloppyApiError.NotFound()
            409 -> FloppyApiError.Conflict()
            429 -> FloppyApiError.RateLimited(retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0L })
            in 500..599 -> FloppyApiError.Server(status)
            in 300..399 -> FloppyApiError.Redirect(status)
            else -> FloppyApiError.Rejected(status)
        }
    }

    override fun toString(): String = "FloppyHttpResponse(status=$status)"
}

fun interface FloppyHttpEngine {
    /** Performs the request exactly as given and never follows a redirect. IO failures are thrown as [IOException]. */
    suspend fun execute(request: FloppyHttpRequest, bearer: String?): FloppyHttpResponse
}

/**
 * Sends requests to a Floppy server. A redirect is an error, never followed, and the bearer token is
 * attached only when the request URL has the same scheme, host and port as the configured server.
 */
class FloppyHttpClient(private val engine: FloppyHttpEngine) {
    /** @param server the configured server; the credential is withheld from any other origin. */
    suspend fun execute(request: FloppyHttpRequest, server: FloppyServerAddress): FloppyHttpResponse {
        val target = request.url.toHttpUrlOrNull() ?: throw FloppyApiError.InvalidResponse()
        val bearer = request.bearer?.takeIf { server.hasSameOrigin(target) }
        val response = try {
            engine.execute(request, bearer)
        } catch (error: IOException) {
            throw FloppyApiError.Network(error)
        }
        if (response.status in 300..399) throw FloppyApiError.Redirect(response.status)
        return response
    }
}

class OkHttpFloppyEngine(client: OkHttpClient, private val appVersion: String) : FloppyHttpEngine {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override suspend fun execute(request: FloppyHttpRequest, bearer: String?): FloppyHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(buildRequest(request, bearer))
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val body = response.body?.let { readFloppyBody(it.source(), it.contentLength()) }.orEmpty()
                            if (continuation.isActive) {
                                continuation.resume(
                                    FloppyHttpResponse(response.code, body, response.header("Retry-After"))
                                )
                            }
                        } catch (error: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }

    internal fun buildRequest(request: FloppyHttpRequest, bearer: String?): Request =
        Request.Builder().url(request.url.toHttpUrl())
            .header("Accept", "application/json")
            .header("User-Agent", "NuvioTV/$appVersion")
            .apply {
                request.headers.forEach { (name, value) -> header(name, value) }
                bearer?.let { header("Authorization", "Bearer $it") }
                when (request.method) {
                    FloppyHttpMethod.GET -> get()
                    FloppyHttpMethod.DELETE -> delete((request.json ?: "{}").toRequestBody("application/json".toMediaType()))
                    FloppyHttpMethod.POST -> post(
                        request.form?.let { fields ->
                            FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()
                        } ?: (request.json ?: "{}").toRequestBody("application/json".toMediaType())
                    )
                }
            }.build()
}

internal fun readFloppyBody(source: BufferedSource, contentLength: Long, maxBytes: Long = 4L * 1024L * 1024L): String {
    if (contentLength > maxBytes) throw IOException("Floppy response exceeds size limit")
    val buffer = Buffer()
    while (true) {
        val count = source.read(buffer, minOf(8_192L, maxBytes - buffer.size + 1L))
        if (count == -1L) return buffer.readUtf8()
        if (buffer.size > maxBytes) throw IOException("Floppy response exceeds size limit")
    }
}
