package ch.lightspots.shunter.core.net

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.setLastModifiedTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageCacheTest {
    @TempDir
    lateinit var tmp: Path

    private lateinit var server: HttpServer
    private val http = HttpDownloader()
    private val hits = AtomicInteger()
    private val picture = ByteArray(1000) { it.toByte() }
    private val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/a.png") { exchange ->
            hits.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, picture.size.toLong())
            exchange.responseBody.use { it.write(picture) }
        }
        server.createContext("/missing.webp") { exchange ->
            hits.incrementAndGet()
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.createContext("/page") { exchange ->
            val body = "<html></html>".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        http.close()
    }

    private fun cache(maxBytes: Long = 5_000) = ImageCache(tmp.resolve("images"), http, maxBytes, allowsUrl = { true })

    @Test
    fun `fetches a picture once and keeps its extension`() = runBlocking {
        val cache = cache()

        val files = (1..5).map { async { cache.get("$baseUrl/a.png") } }.awaitAll()

        val file = files.distinct().single()!!
        assertContentEquals(picture, file.readBytes())
        assertTrue(file.name.endsWith(".png"))
        assertEquals(1, hits.get())
        assertEquals(file, cache().get("$baseUrl/a.png"), "a new cache finds it on disk")
        assertEquals(1, hits.get())
    }

    @Test
    fun `failures are remembered, not retried`() = runBlocking {
        val cache = cache()

        assertNull(cache.get("$baseUrl/missing.webp"))
        assertNull(cache.get("$baseUrl/missing.webp"))
        assertEquals(1, hits.get())
    }

    @Test
    fun `refuses web pages and pictures that are too large`() = runBlocking {
        assertNull(cache().get("$baseUrl/page"))
        assertNull(cache(maxBytes = 999).get("$baseUrl/a.png"))
        assertFalse(tmp.resolve("images").exists() && tmp.resolve("images").toFile().list()!!.isNotEmpty())
    }

    @Test
    fun `only https by default`() = runBlocking {
        assertNull(ImageCache(tmp.resolve("images"), http).get("$baseUrl/a.png"))
        assertEquals(0, hits.get())
    }

    @Test
    fun `prune removes old pictures`() = runBlocking {
        val cache = cache()
        val file = cache.get("$baseUrl/a.png")!!
        file.setLastModifiedTime(FileTime.from(Instant.now().minus(Duration.ofDays(40))))

        cache.prune(Duration.ofDays(30))

        assertFalse(file.exists())
    }
}
