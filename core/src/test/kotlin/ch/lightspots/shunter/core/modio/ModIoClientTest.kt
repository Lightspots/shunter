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
    private var subscribed = (1L..150L).toMutableList()

    private val apiUrl get() = "http://127.0.0.1:${server.address.port}/v1"

    data class Request(
        val method: String,
        val path: String,
        val params: Map<String, String>,
        val authorization: String?,
        val language: String?,
        val contentType: String?,
        val body: String,
    )

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/") { exchange ->
            val request = Request(
                method = exchange.requestMethod,
                path = exchange.requestURI.path.removePrefix("/v1"),
                params = params(exchange.requestURI.rawQuery),
                authorization = exchange.requestHeaders.getFirst("Authorization"),
                language = exchange.requestHeaders.getFirst("Accept-Language"),
                contentType = exchange.requestHeaders.getFirst("Content-Type"),
                body = String(exchange.requestBody.readAllBytes()),
            )
            requests += request
            val modPath = Regex("/games/10640/mods/(\\d+)(/.*)?").matchEntire(request.path)
            val id = modPath?.groupValues?.get(1)?.toLong()
            when {
                request.authorization == "Bearer $READ_ONLY_TOKEN" && request.method == "GET" -> respond(
                    exchange,
                    200,
                    page(listOf(), 0, 0),
                )

                // Like mod.io, which wants exactly this type, without a charset
                request.method != "GET" && request.contentType != "application/x-www-form-urlencoded" ->
                    respond(exchange, 415, """{"error": {"code": 415, "message": "Incorrect Content-Type header in request."}}""")

                request.authorization != "Bearer $TOKEN" ->
                    respond(exchange, 401, """{"error": {"code": 401, "error_ref": 11005, "message": "Token is invalid."}}""")

                id != null && modPath.groupValues[2] == "" && request.method == "GET" ->
                    if (id < 1000) respond(exchange, 200, modJson(id)) else respond(exchange, 404, """{"error": {"code": 404}}""")

                id != null && modPath.groupValues[2] == "/dependencies" -> respond(
                    exchange,
                    200,
                    page(listOf(id + 1, id + 2, id + 1), 0, 3),
                )

                id != null && modPath.groupValues[2] == "/subscribe" && request.method == "POST" -> {
                    val already = id in subscribed
                    if (!already) subscribed.add(id)
                    respond(exchange, if (already) 200 else 201, modJson(id))
                }

                id != null && modPath.groupValues[2] == "/subscribe" && request.method == "DELETE" ->
                    if (subscribed.remove(id)) {
                        exchange.sendResponseHeaders(204, -1)
                        exchange.close()
                    } else {
                        respond(exchange, 400, """{"error": {"code": 400, "error_ref": 15005, "message": "Not subscribed."}}""")
                    }

                request.path == "/me" -> respond(exchange, 200, """{"id": 7, "name_id": "anna", "username": "Anna"}""")

                request.path == "/me/subscribed" -> {
                    val offset = request.params["_offset"]!!.toInt()
                    val limit = request.params["_limit"]!!.toInt()
                    respond(exchange, 200, page(subscribed.drop(offset).take(limit), offset, subscribed.size))
                }

                request.path == "/games/10640/mods" && request.params["name_id"] != null ->
                    respond(exchange, 200, if (request.params["name_id"] == "mod-5") page(listOf(5), 0, 1) else page(listOf(), 0, 0))

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
        subscribed = mutableListOf(1L, 2L)
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

    @Test
    fun `finds mods by id, name id and address`() = runBlocking {
        val client = client()

        assertEquals(5L, client.findMod("5")?.id)
        assertEquals(5L, client.findMod("mod-5")?.id)
        assertEquals(5L, client.findMod("https://mod.io/g/transportfever3/m/mod-5/?tab=files#x")?.id)
        assertNull(client.findMod("1234"), "404")
        assertNull(client.findMod("unknown"))
    }

    @Test
    fun `lists dependencies once, recursively`() = runBlocking {
        assertEquals(listOf(6L, 7L), client().dependencies(5).map { it.id })
        assertEquals("true", requests.single().params["recursive"])
    }

    @Test
    fun `subscribes with dependencies and unsubscribes`() = runBlocking {
        subscribed = mutableListOf(1L)
        val client = client()

        val result = client.subscribe(5)
        assertEquals(ModIoSubscribeResult(result.mod, alreadySubscribed = false), result)
        assertEquals(5L, result.mod.id)
        val post = requests.last()
        assertEquals("POST", post.method)
        assertEquals("include_dependencies=true", post.body)
        assertTrue(client.subscribe(5).alreadySubscribed)

        assertTrue(client.unsubscribe(5))
        assertEquals("DELETE", requests.last().method)
        assertFalse(client.unsubscribe(5), "not subscribed any more")
        assertEquals(listOf(1L), subscribed)
    }

    @Test
    fun `a change refused for a read-only token says so`() = runBlocking {
        val client = ModIoClient(http, ModIoLogin(READ_ONLY_TOKEN), apiUrl)
        client.subscribedMods()

        val e = assertThrows<ModIoAuthException> { client.subscribe(5) }
        assertTrue(e.message!!.contains("read-only"), e.message)
    }

    private fun manager() = ModManager(
        GamePaths(null, tmp.resolve("local/mods"), null, tmp.resolve("modio")),
        AppDirs.under(tmp.resolve("app")),
        http,
        modIoApiUrl = apiUrl,
    )

    private fun params(query: String?): Map<String, String> = query.orEmpty()
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
        const val READ_ONLY_TOKEN = "read-only-token"
    }
}
