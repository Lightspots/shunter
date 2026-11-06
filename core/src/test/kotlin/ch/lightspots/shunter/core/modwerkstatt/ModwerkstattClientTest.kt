package ch.lightspots.shunter.core.modwerkstatt

import ch.lightspots.shunter.core.ModManager
import ch.lightspots.shunter.core.TestFiles
import ch.lightspots.shunter.core.feed.FeedService
import ch.lightspots.shunter.core.feed.FeedSignInException
import ch.lightspots.shunter.core.feed.FeedSource
import ch.lightspots.shunter.core.feed.RemoteFile
import ch.lightspots.shunter.core.feed.RemoteMod
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
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.exists
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModwerkstattClientTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var server: HttpServer
    private val http = HttpDownloader()
    private val requests = CopyOnWriteArrayList<Request>()
    private lateinit var archive: ByteArray

    /** Tokens the fake server accepts. */
    private val validTokens = mutableSetOf(TOKEN)

    private val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    data class Request(val method: String, val path: String, val authorization: String?, val contentType: String?, val body: String)

    @BeforeEach
    fun start() {
        archive = TestFiles.zip(tmp.resolve("src/mw_test.zip"), TestFiles.inFolder("mw_test", TestFiles.modFiles("mw_test"))).readBytes()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val request = Request(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                authorization = exchange.requestHeaders.getFirst("Authorization"),
                contentType = exchange.requestHeaders.getFirst("Content-Type"),
                body = String(exchange.requestBody.readAllBytes()),
            )
            requests += request
            val token = request.authorization?.removePrefix("Bearer ")
            when (request.path) {
                "/api/login/request" -> respond(exchange, 200, """{"ok": true}""")

                "/api/login/confirm" ->
                    if ("\"code\":\"123456\"" in request.body) {
                        respond(
                            exchange,
                            200,
                            """{"token": "$TOKEN", "expires_at": "2099-01-01T00:00:00+00:00",
                               "user": {"name": "Anna", "membership_until": "2027-01-31"}}""",
                        )
                    } else {
                        respond(exchange, 422, """{"error": "invalid_code"}""")
                    }

                "/api/me" ->
                    if (token in validTokens) {
                        respond(exchange, 200, """{"user": {"name": "Anna B.", "membership_until": "2027-06-30"}}""")
                    } else {
                        respond(exchange, 401, """{"error": "token_invalid"}""")
                    }

                "/api/logout" -> {
                    validTokens.remove(token)
                    respond(exchange, 200, "{}")
                }

                "/json/mwmanager" ->
                    if (token == null || token in validTokens) {
                        respond(exchange, 200, """[{"id": 1, "modId": "mw_test", "title": "Test", "revision": "1"}]""")
                    } else {
                        respond(exchange, 401, """{"error": "token_invalid"}""")
                    }

                "/download/mw_test.zip" -> {
                    exchange.sendResponseHeaders(200, archive.size.toLong())
                    exchange.responseBody.use { it.write(archive) }
                }

                else -> respond(exchange, 404, "\"\"")
            }
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        http.close()
        server.stop(0)
    }

    private fun manager(sendsToken: (String) -> Boolean = { true }, apiUrl: String = "$baseUrl/api") = ModManager(
        GamePaths(null, tmp.resolve("local/mods"), null, null),
        AppDirs.under(tmp.resolve("app")),
        http,
        modwerkstattApiUrl = apiUrl,
        sendsModwerkstattToken = sendsToken,
    )

    private fun signedIn(manager: ModManager, expiresAt: Instant = Instant.now().plus(Duration.ofDays(1))) =
        manager.modwerkstattLogins.save(ModwerkstattLogin(TOKEN, "anna@example.org", "Anna", null, expiresAt.epochSecond))

    @Test
    fun `signs in with a mailed code and stores the token readable only by the user`() = runBlocking {
        val manager = manager()

        manager.modwerkstattRequestCode(" Anna@Example.org ")
        val login = manager.modwerkstattConfirm("Anna@Example.org", " 123456 ", "shunter test")

        assertEquals(ModwerkstattLogin(TOKEN, "anna@example.org", "Anna", "2027-01-31", 4070908800), login)
        assertEquals(login, manager.modwerkstattLogin())
        assertEquals(PosixFilePermissions.fromString("rw-------"), manager.modwerkstattLogins.file.getPosixFilePermissions())
        val (codeRequest, confirm) = requests
        assertEquals("POST", codeRequest.method)
        assertEquals("application/json", codeRequest.contentType)
        assertEquals("""{"email":"anna@example.org"}""", codeRequest.body)
        assertEquals("""{"email":"anna@example.org","code":"123456","device_name":"shunter test"}""", confirm.body)
        assertFalse(TOKEN in login.toString())
    }

    @Test
    fun `a wrong code stores nothing`() = runBlocking {
        val manager = manager()

        val e = assertThrows<ModwerkstattException> { runBlocking { manager.modwerkstattConfirm("anna@example.org", "000000") } }

        assertEquals("invalid_code", e.error)
        assertEquals("The code is wrong or has expired.", e.message)
        assertFalse(manager.modwerkstattLogins.file.exists())
    }

    @Test
    fun `status updates the account and forgets a token that is no longer accepted`() = runBlocking {
        val manager = manager()
        signedIn(manager)

        val account = assertNotNull(manager.modwerkstattAccount())
        assertEquals("Anna B.", account.name)
        assertEquals("2027-06-30", manager.modwerkstattLogins.load()?.membershipUntil)
        assertEquals("Bearer $TOKEN", requests.single().authorization)

        validTokens.clear()
        assertThrows<ModwerkstattAuthException> { runBlocking { manager.modwerkstattAccount() } }
        assertNull(manager.modwerkstattLogins.load())
    }

    @Test
    fun `an expired sign-in is not used`() = runBlocking {
        val manager = manager()
        signedIn(manager, expiresAt = Instant.now().minusSeconds(60))

        assertNull(manager.modwerkstattLogin())
        assertEquals(emptyMap(), manager.feedHeaders(FeedSource.MODWERKSTATT))
        assertThrows<ModwerkstattAuthException> { runBlocking { manager.modwerkstattAccount() } }
        assertTrue(requests.isEmpty(), "an expired token is not sent")
    }

    @Test
    fun `logout ends the session on the server and forgets the token, also when the server cannot be reached`() = runBlocking {
        val manager = manager()
        assertFalse(manager.modwerkstattLogout())

        signedIn(manager)
        assertTrue(manager.modwerkstattLogout())
        assertEquals(Request("POST", "/api/logout", "Bearer $TOKEN", "application/json", "{}"), requests.single())
        assertFalse(TOKEN in validTokens)
        assertNull(manager.modwerkstattLogins.load())

        // Port 1 is never open
        val offline = manager(apiUrl = "http://127.0.0.1:1/api")
        signedIn(offline)
        assertTrue(offline.modwerkstattLogout())
        assertNull(offline.modwerkstattLogins.load())
    }

    @Test
    fun `the token goes only to modwerkstatt downloads and the modwerkstatt feed`() = runBlocking {
        val manager = manager(sendsToken = { it.startsWith(baseUrl) })
        signedIn(manager)
        assertEquals(mapOf("Authorization" to "Bearer $TOKEN"), manager(sendsToken = { true }).feedHeaders(FeedSource.MODWERKSTATT))
        assertEquals(emptyMap(), manager(sendsToken = { true }).feedHeaders(FeedSource.TFNET))

        val file = RemoteFile("1-r1", "mw_test.zip", "$baseUrl/download/mw_test.zip")
        manager.installRemote(RemoteMod(FeedSource.MODWERKSTATT, "1", "Test", files = listOf(file)))
        manager.installRemote(RemoteMod(FeedSource.TFNET, "2", "Test", files = listOf(file.copy(id = "2"))))

        assertEquals(listOf("Bearer $TOKEN", null), requests.map { it.authorization })
        assertTrue(tmp.resolve("local/mods/mw_test/mod.json").exists())
    }

    @Test
    fun `a refused sign-in on the feed is reported as such`() = runBlocking<Unit> {
        val appDirs = AppDirs.under(tmp.resolve("app"))
        val feeds =
            FeedService(appDirs, http, headers = { mapOf("Authorization" to "Bearer revoked") }, urls = { "$baseUrl/json/mwmanager" })

        assertThrows<FeedSignInException> { runBlocking { feeds.load(FeedSource.MODWERKSTATT) } }

        appDirs.feeds.resolve("modwerkstatt.json").also { it.parent.toFile().mkdirs() }.writeText("[]")
        val result = feeds.load(FeedSource.MODWERKSTATT, maxAge = Duration.ZERO)
        assertTrue(result.signInRefused)
        assertNotNull(result.error)
    }

    @Test
    fun `only https modwerkstatt urls get the token`() {
        assertTrue(ModwerkstattClient.isModwerkstattUrl("https://modwerkstatt.com/download/tpf3/a.zip"))
        assertTrue(ModwerkstattClient.isModwerkstattUrl("https://cdn.modwerkstatt.com/a.zip"))
        assertFalse(ModwerkstattClient.isModwerkstattUrl("http://modwerkstatt.com/download/a.zip"))
        assertFalse(ModwerkstattClient.isModwerkstattUrl("https://modwerkstatt.com.example.org/a.zip"))
        assertFalse(ModwerkstattClient.isModwerkstattUrl("https://evilmodwerkstatt.com/a.zip"))
        assertFalse(ModwerkstattClient.isModwerkstattUrl("not a url"))
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val TOKEN = "mw-test-token"
    }
}
