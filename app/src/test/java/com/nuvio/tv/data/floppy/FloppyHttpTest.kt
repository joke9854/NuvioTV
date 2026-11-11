package com.nuvio.tv.data.floppy

import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloppyHttpTest {
    private fun MockWebServer.floppyClient() =
        FloppyHttpClient(OkHttpFloppyEngine(OkHttpClient(), "test"))

    private fun MockWebServer.address() = FloppyServerAddress.parse("http://127.0.0.1:$port")!!

    private fun withServer(block: suspend (MockWebServer) -> Unit) = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            block(server)
        }
    }

    @Test
    fun `a 302 is not followed and maps to Redirect`() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:${server.port}/elsewhere"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val address = server.address()

        val failure = expectFloppyFailure<FloppyApiError.Redirect> {
            server.floppyClient().execute(
                FloppyHttpRequest(FloppyHttpMethod.GET, address.url("/api/v1/info/"), bearer = "token-value"), address
            )
        }

        assertEquals(302, failure.status)
        assertEquals("the redirect target is never requested", 1, server.requestCount)
    }

    @Test
    fun `a redirect to another host never receives the credential`() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "http://other.example.invalid/steal"))
        val address = server.address()

        expectFloppyFailure<FloppyApiError.Redirect> {
            server.floppyClient().execute(
                FloppyHttpRequest(FloppyHttpMethod.POST, address.url("/api/v1/scrobble/"), json = "{}", bearer = "token-value"), address
            )
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the bearer token is sent to the configured origin`() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val address = server.address()

        server.floppyClient().execute(
            FloppyHttpRequest(FloppyHttpMethod.GET, address.url("/api/v1/sync/connections/"), bearer = "token-value"), address
        )

        assertEquals("Bearer token-value", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `the bearer token is withheld from any other origin`() = runTest {
        val engine = FloppyTestEngine()
        val client = FloppyHttpClient(engine)
        val server = FloppyServerAddress.parse("https://floppy.example.com")!!

        listOf(
            "https://evil.example.com/api/v1/info/",
            "https://floppy.example.com:8443/api/v1/info/",
            "http://floppy.example.com/api/v1/info/",
            "https://floppy.example.com.evil.com/api/v1/info/"
        ).forEach { url ->
            client.execute(FloppyHttpRequest(FloppyHttpMethod.GET, url, bearer = "token-value"), server)
        }
        client.execute(
            FloppyHttpRequest(FloppyHttpMethod.GET, "https://floppy.example.com/api/v1/info/", bearer = "token-value"), server
        )

        assertTrue(engine.requests.dropLast(1).all { it.bearer == null })
        assertEquals("token-value", engine.requests.last().bearer)
    }

    @Test
    fun `statuses map to the matching error`() = runTest {
        fun failureFor(status: Int, retryAfter: String? = null) =
            runCatching { FloppyHttpResponse(status, "secret body", retryAfter).requireSuccess() }.exceptionOrNull()

        assertTrue(failureFor(401) is FloppyApiError.Unauthorized)
        assertTrue(failureFor(403) is FloppyApiError.Forbidden)
        assertTrue(failureFor(404) is FloppyApiError.NotFound)
        assertTrue(failureFor(409) is FloppyApiError.Conflict)
        assertTrue(failureFor(503) is FloppyApiError.Server)
        assertTrue(failureFor(302) is FloppyApiError.Redirect)
        assertTrue(failureFor(400) is FloppyApiError.Rejected)
        assertEquals(30L, (failureFor(429, "30") as FloppyApiError.RateLimited).retryAfterSeconds)
        assertNull((failureFor(429, "soon") as FloppyApiError.RateLimited).retryAfterSeconds)
        assertTrue(!failureFor(503)!!.message!!.contains("secret"))
    }

    @Test
    fun `an IO failure maps to Network and an unreadable URL to InvalidResponse`() = runTest {
        val engine = FloppyTestEngine()
        engine.handler = { throw java.io.IOException("connection reset") }
        val client = FloppyHttpClient(engine)
        val server = FloppyServerAddress.parse("https://floppy.example.com")!!

        expectFloppyFailure<FloppyApiError.Network> {
            client.execute(FloppyHttpRequest(FloppyHttpMethod.GET, "https://floppy.example.com/x"), server)
        }
        expectFloppyFailure<FloppyApiError.InvalidResponse> {
            client.execute(FloppyHttpRequest(FloppyHttpMethod.GET, "not a url"), server)
        }
    }
}
