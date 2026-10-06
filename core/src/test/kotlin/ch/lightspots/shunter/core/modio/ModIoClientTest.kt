package ch.lightspots.shunter.core.modio

import ch.lightspots.shunter.core.ModManager
import ch.lightspots.shunter.core.TestFiles
import ch.lightspots.shunter.core.net.HttpDownloader
import ch.lightspots.shunter.core.paths.AppDirs
import ch.lightspots.shunter.core.paths.GamePaths
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModIoClientTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var server: HttpServer
    private val http = HttpDownloader()
    private val requests = CopyOnWriteArrayList<Request>()

    /** Subscriptions the fake server knows, as mod ids. */
    private var subscribed = (1L..150L).toList()

    private val apiUrl get() = "http://127.0.0.1:${server.address.port}/v1"

    data class Request(val path: String, val params: Map<String, String>, val authorization: String?, val language: String?)

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/") { exchange ->
            val request = Request(
                path = exchange.requestURI.path.removePrefix("/v1"),
                params = params(exchange),
                authorization = exchange.requestHeaders.getFirst("Authorization"),
                language = exchange.requestHeaders.getFirst("Accept-Language"),
            )
            requests += request
            when {
                request.authorization != "Bearer $TOKEN" ->
                    respond(exchange, 401, """{"error": {"code": 401, "error_ref": 11005, "message": "Token is invalid."}}""")

                request.path == "/me" -> respond(exchange, 200, """{"id": 7, "name_id": "anna", "username": "Anna"}""")

                request.path == "/me/subscribed" -> {
                    val offset = request.params["_offset"]!!.toInt()
                    val limit = request.params["_limit"]!!.toInt()
                    respond(exchange, 200, page(subscribed.drop(offset).take(limit), offset, subscribed.size))
                }

                request.path == "/games/10640/mods" -> respond(exchange, 200, page(listOf(42), 0, 300))

                else -> respond(exchange, 500, """{"error": {"code": 500, "message": "Something broke."}}""")
            }
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        http.close()
        server.stop(0)
    }

    private fun client(token: String = TOKEN) = ModIoClient(http, ModIoLogin(token), apiUrl, language = "de")

    @Test
    fun `sends the token as bearer header, never in the url`() = runBlocking {
        assertEquals(ModIoUser(7, "Anna"), client().me())

        val request = requests.single()
        assertEquals("Bearer $TOKEN", request.authorization)
        assertEquals("de", request.language)
        assertTrue(request.params.values.none { TOKEN in it })
    }

    @Test
    fun `reads all pages of the subscriptions, for TF3 only`() = runBlocking {
        val mods = client().subscribedMods()

        assertEquals((1L..150L).toList(), mods.map { it.id })
        assertEquals(listOf("0", "100"), requests.map { it.params["_offset"] })
        assertTrue(requests.all { it.params["game_id"] == "10640" })
    }

    @Test
    fun `parses mods`() = runBlocking {
        val page = client().searchMods("Brücke Bahn", limit = 10)

        assertEquals(300, page.total)
        val mod = page.mods.single()
        assertEquals(
            ModIoMod(
                42, "Mod 42", "Summary 42", "author42", "https://mod.io/g/tf3/m/mod-42", 1_700_000_042, "1.2", 2048, 5,
                listOf(
                    "Bridge",
                ),
            ),
            mod,
        )
        assertEquals("42", mod.folderName)
        val params = requests.single().params
        assertEquals("Brücke Bahn", params["_q"])
        assertEquals("10", params["_limit"])
    }

    @Test
    fun `a refused token is reported as such, with the server's reason and without the token`() = runBlocking {
        val e = assertThrows<ModIoAuthException> { client("wrong-token").me() }

        assertTrue(e.message!!.contains("Token is invalid."), e.message)
        assertFalse(e.message!!.contains("wrong-token"))
    }

    @Test
    fun `other errors carry the server's message`() = runBlocking {
        val e = assertThrows<ModIoException> { ModIoClient(http, ModIoLogin(TOKEN), "$apiUrl/broken").me() }

        assertTrue(e.message!!.contains("HTTP 500") && e.message!!.contains("Something broke."), e.message)
    }

    @Test
    fun `login stores the token only when mod io accepts it, logout removes it`() = runBlocking {
        val manager = manager()

        assertThrows<ModIoAuthException> { manager.modIoLogin("wrong-token") }
        assertNull(manager.modIo())

        assertEquals("Anna", manager.modIoLogin("  $TOKEN\n").username)
        assertEquals(ModIoLogin(TOKEN, "Anna"), manager.modIoLogins.load())
        assertNotNull(manager.modIo())

        assertTrue(manager.modIoLogout())
        assertFalse(manager.modIoLogins.file.exists())
        assertFalse(manager.modIoLogout())
    }

    @Test
    fun `subscriptions are matched with the mod io folder by id`() = runBlocking {
        subscribed = listOf(1L, 2L)
        val manager = manager()
        assertNull(manager.modIoSubscriptions(), "not signed in")

        TestFiles.writeTree(tmp.resolve("modio/2"), mapOf("mod.json" to TestFiles.modJson("two")))
        TestFiles.writeTree(tmp.resolve("modio/3"), mapOf("mod.json" to TestFiles.modJson("three")))
        manager.modIoLogins.save(ModIoLogin(TOKEN))

        val subscriptions = assertNotNull(manager.modIoSubscriptions())
        assertEquals(listOf(1L), subscriptions.notDownloaded.map { it.id })
        assertEquals(listOf("3"), subscriptions.notSubscribed.map { it.folderName })
        val two = subscriptions.installed.single { it.folderName == "2" }
        assertEquals("Mod 2", subscriptions.subscriptionOf(two)?.name)
    }

    private fun manager() = ModManager(
        GamePaths(null, tmp.resolve("local/mods"), null, tmp.resolve("modio")),
        AppDirs.under(tmp.resolve("app")),
        http,
        modIoApiUrl = apiUrl,
    )

    private fun params(exchange: HttpExchange): Map<String, String> = exchange.requestURI.rawQuery.orEmpty()
        .split('&')
        .filter { it.isNotEmpty() }
        .associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }

    private fun page(ids: List<Long>, offset: Int, total: Int) = """
        {
          "data": [${ids.joinToString(",", transform = ::modJson)}],
          "result_count": ${ids.size}, "result_offset": $offset, "result_limit": 100, "result_total": $total
        }
    """.trimIndent()

    private fun modJson(id: Long) = """
        {
          "id": $id, "game_id": 10640, "status": 1, "visible": 1,
          "submitted_by": {"id": 3, "name_id": "author$id", "username": "author$id"},
          "date_added": 1600000000, "date_updated": ${1_700_000_000 + id}, "date_live": 1600000000,
          "name": "Mod $id", "name_id": "mod-$id", "summary": "Summary $id", "profile_url": "https://mod.io/g/tf3/m/mod-$id",
          "modfile": {"id": 9, "mod_id": $id, "filesize": 2048, "filehash": {"md5": "abc"}, "version": " 1.2 ",
                      "download": {"binary_url": "https://example.invalid/file.zip", "date_expires": 1}},
          "stats": {"mod_id": $id, "downloads_total": 10, "subscribers_total": 5},
          "tags": [{"name": "Bridge", "name_localized": "Brücke"}]
        }
    """.trimIndent()

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val TOKEN = "test-token-123"
    }
}
