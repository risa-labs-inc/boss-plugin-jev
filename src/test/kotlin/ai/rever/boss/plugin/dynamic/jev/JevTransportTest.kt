package ai.rever.boss.plugin.dynamic.jev

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class JevTransportTest {
    @Test
    fun `authorization and JSON go only to configured request destination`() = runTest {
        var authorization: String? = null
        var contentType: String? = null
        var requestBody: ByteArray? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/systemone") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            requestBody = exchange.requestBody.readAllBytes()
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("{}".encodeToByteArray()) }
        }
        server.createContext("/other") { exchange ->
            assertNull(exchange.requestHeaders.getFirst("Authorization"))
            exchange.sendResponseHeaders(500, -1)
        }
        server.start()
        try {
            val endpoint = URI.create("http://127.0.0.1:${server.address.port}/systemone")
            val body = "{\"model\":\"typesafe/jev-1.13\"}".encodeToByteArray()
            JdkJevTransport(endpoint = endpoint).post(body, "top-secret", 2_000, 1024)
            assertEquals("Bearer top-secret", authorization)
            assertEquals("application/json", contentType)
            assertContentEquals(body, requestBody)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `maps auth rate and upstream errors without response bodies`() = runTest {
        val statuses = mapOf(401 to "AUTH_ERROR", 403 to "AUTH_ERROR", 422 to "UPSTREAM_INVALID_INPUT", 429 to "RATE_LIMITED", 500 to "UPSTREAM_ERROR")
        for ((status, code) in statuses) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                val raw = "body-with-secret-should-not-surface"
                exchange.sendResponseHeaders(status, raw.length.toLong())
                exchange.responseBody.use { it.write(raw.encodeToByteArray()) }
            }
            server.start()
            try {
                val failure = assertFailsWith<JevFailure> {
                    JdkJevTransport(endpoint = URI.create("http://127.0.0.1:${server.address.port}/"))
                        .post("{}".encodeToByteArray(), "key", 2_000, 1024)
                }
                assertEquals(code, failure.code)
                assertEquals(false, failure.message.contains("body-with-secret"))
            } finally { server.stop(0) }
        }
    }

    @Test
    fun `rejects declared oversized response before buffering body`() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val raw = ByteArray(2048) { 'x'.code.toByte() }
            exchange.sendResponseHeaders(200, raw.size.toLong())
            runCatching { exchange.responseBody.use { it.write(raw) } }
        }
        server.start()
        try {
            val failure = assertFailsWith<JevFailure> {
                JdkJevTransport(endpoint = URI.create("http://127.0.0.1:${server.address.port}/"))
                    .post("{}".encodeToByteArray(), "key", 2_000, 128)
            }
            assertEquals("RESPONSE_TOO_LARGE", failure.code)
        } finally { server.stop(0) }
    }

    @Test
    fun `bounds chunked response with no content length while receiving`() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val raw = ByteArray(2048) { 'x'.code.toByte() }
            exchange.sendResponseHeaders(200, 0)
            runCatching { exchange.responseBody.use { it.write(raw) } }
        }
        server.start()
        try {
            val failure = assertFailsWith<JevFailure> {
                JdkJevTransport(endpoint = URI.create("http://127.0.0.1:${server.address.port}/"))
                    .post("{}".encodeToByteArray(), "key", 2_000, 128)
            }
            assertEquals("RESPONSE_TOO_LARGE", failure.code)
        } finally { server.stop(0) }
    }

    @Test
    fun `does not follow redirects or forward authorization`() = runTest {
        val followed = AtomicBoolean(false)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/start") { exchange ->
            exchange.responseHeaders.add("Location", "/target")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/target") { exchange ->
            followed.set(true)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val failure = assertFailsWith<JevFailure> {
                JdkJevTransport(endpoint = URI.create("http://127.0.0.1:${server.address.port}/start"))
                    .post("{}".encodeToByteArray(), "key", 2_000, 128)
            }
            assertEquals("UPSTREAM_ERROR", failure.code)
            assertFalse(followed.get())
        } finally { server.stop(0) }
    }

    @Test
    fun `post after close fails without sending an HTTP request`() = runTest {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val transport = JdkJevTransport(endpoint = URI.create("http://127.0.0.1:${server.address.port}/"))
            transport.cancelAll()
            val failure = assertFailsWith<JevFailure> {
                transport.post("{}".encodeToByteArray(), "key", 2_000, 128)
            }
            assertEquals("SERVICE_UNAVAILABLE", failure.code)
            assertEquals(0, requests.get())
        } finally { server.stop(0) }
    }

    @Test
    fun `close cancels an active HTTP future`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            entered.complete(Unit)
            release.await()
            runCatching {
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.use { it.write("{}".encodeToByteArray()) }
            }
        }
        server.start()
        try {
            val transport = JdkJevTransport(endpoint = URI.create("http://127.0.0.1:${server.address.port}/"))
            val request = async {
                assertFailsWith<CancellationException> {
                    transport.post("{}".encodeToByteArray(), "key", 2_000, 128)
                }
            }
            entered.await()
            transport.cancelAll()
            release.countDown()
            request.await()
        } finally {
            release.countDown()
            server.stop(0)
        }
    }
}
