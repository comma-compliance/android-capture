package com.commacompliance.archiver.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The HttpURLConnection-backed client must NOT follow redirects: these requests
 * carry a bearer credential, and a followed 3xx could replay the `Authorization`
 * header to wherever `Location` points (cross-origin, or downgraded to cleartext).
 * A 3xx must surface to the caller as a (non-success) status instead.
 *
 * Uses a tiny raw-socket HTTP server (the Android unit-test classpath does not
 * expose com.sun.net.httpserver) that always answers a 302. If the client wrongly
 * followed it, a SECOND request would arrive; the test asserts exactly one.
 */
class UrlConnectionHttpClientTest {

    private lateinit var server: ServerSocket
    private val requestLines = CopyOnWriteArrayList<String>()

    @Before
    fun startServer() {
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                handle(socket)
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            val reader = s.getInputStream().bufferedReader()
            // Record the request line (e.g. "POST /entry HTTP/1.1") then drain headers.
            val requestLine = reader.readLine() ?: return
            requestLines.add(requestLine)
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            // Always redirect; the client must NOT follow it.
            val body = "redirect"
            val response = buildString {
                append("HTTP/1.1 302 Found\r\n")
                append("Location: /leak\r\n")
                append("Content-Length: ${body.length}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
                append(body)
            }
            s.getOutputStream().apply {
                write(response.toByteArray(Charsets.UTF_8))
                flush()
            }
        }
    }

    @After
    fun stopServer() {
        server.close()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"

    @Test
    fun post_does_not_follow_redirect_and_surfaces_the_3xx() {
        val client = UrlConnectionHttpClient()
        val response = client.postJson(
            url = url("/entry"),
            body = "{}",
            headers = mapOf("Authorization" to "Bearer secret-token"),
        )
        // The 302 is surfaced, not followed - the caller treats it as a non-success.
        assertEquals(302, response.code)
        // Exactly one request reached the server: the redirect target was never fetched.
        assertEquals(1, requestLines.size)
        assertFalse(requestLines.any { it.contains("/leak") })
    }

    @Test
    fun get_does_not_follow_redirect_and_surfaces_the_3xx() {
        val client = UrlConnectionHttpClient()
        val response = client.getJson(
            url = url("/entry"),
            headers = mapOf("Authorization" to "Bearer secret-token"),
        )
        assertEquals(302, response.code)
        assertEquals(1, requestLines.size)
        assertFalse(requestLines.any { it.contains("/leak") })
    }
}
