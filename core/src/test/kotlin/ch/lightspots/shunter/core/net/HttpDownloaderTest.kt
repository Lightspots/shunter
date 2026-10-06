package ch.lightspots.shunter.core.net

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpDownloaderTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var server: HttpServer
    private val http = HttpDownloader()
    private val releaseSlow = CountDownLatch(1)
    private val hits = AtomicInteger()
    private val payload = ByteArray(200_000) { (it % 251).toByte() }
    private val payloadSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload))
    private val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { exchange ->
            hits.incrementAndGet()
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/file")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/slow") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use {
                it.write(payload, 0, 1000)
                it.flush()
                releaseSlow.await()
            }
        }
        server.createContext("/api") { exchange ->
            val ok = exchange.requestHeaders.getFirst("Authorization") == "Bearer abc"
            val body = (if (ok) "{}" else "{\"error\": \"no\"}").toByteArray()
            exchange.sendResponseHeaders(if (ok) 200 else 401, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/feed") { exchange ->
            val body = "{\"ok\": \"äö\"}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        http.close()
        releaseSlow.countDown()
        server.stop(0)
    }

    @Test
    fun `downloads, follows redirects and verifies size and checksum`() = runBlocking {
        val progress = mutableListOf<Long>()
        val dest = tmp.resolve("dl/file.zip")

        val sha = http.download("$baseUrl/redirect", dest, payload.size.toLong(), payloadSha.uppercase()) { done, _ ->
            progress += done
        }

        assertEquals(payloadSha, sha)
        assertContentEquals(payload, dest.readBytes())
        assertEquals(payload.size.toLong(), progress.last())
        assertEquals(listOf(dest), dest.parent.listDirectoryEntries(), "no .part file left behind")
    }

    @Test
    fun `reuses an existing file with the expected checksum`() = runBlocking {
        val dest = tmp.resolve("file.zip")
        http.download("$baseUrl/file", dest, expectedSha256 = payloadSha)
        http.download("$baseUrl/file", dest, expectedSha256 = payloadSha)

        assertEquals(1, hits.get())
    }

    @Test
    fun `checksum or size mismatch fails and leaves no file`() = runBlocking {
        val dest = tmp.resolve("file.zip")

        assertThrows<DownloadException> { http.download("$baseUrl/file", dest, expectedSha256 = "00".repeat(32)) }
        assertThrows<DownloadException> { http.download("$baseUrl/file", dest, expectedSize = 5) }

        assertFalse(dest.exists())
        assertTrue(tmp.listDirectoryEntries().isEmpty())
    }

    @Test
    fun `http errors fail`() = runBlocking {
        val e = assertThrows<DownloadException> { http.download("$baseUrl/missing", tmp.resolve("x")) }
        assertTrue(e.message!!.contains("404"))
    }

    @Test
    fun `fetches text as utf-8`() = runBlocking {
        assertEquals("{\"ok\": \"äö\"}", http.fetchText("$baseUrl/feed"))
    }

    @Test
    fun `fetchText sends headers and keeps the body of error responses`() = runBlocking {
        assertEquals("{}", http.fetchText("$baseUrl/api", mapOf("Authorization" to "Bearer abc")))

        val e = assertThrows<HttpStatusException> { http.fetchText("$baseUrl/api") }
        assertEquals(401, e.status)
        assertEquals("{\"error\": \"no\"}", e.body)
    }

    @Test
    fun `cancelling a download removes the partial file`() = runBlocking {
        val dest = tmp.resolve("file.zip")
        val started = CompletableDeferred<Unit>()
        val job = launch {
            http.download("$baseUrl/slow", dest, payload.size.toLong()) { _, _ -> started.complete(Unit) }
        }
        started.await()
        job.cancelAndJoin()

        assertFalse(dest.exists())
        assertTrue(tmp.listDirectoryEntries().isEmpty())
    }
}
