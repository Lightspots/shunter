package ch.lightspots.shunter.core.modwerkstatt

import ch.lightspots.shunter.core.get
import ch.lightspots.shunter.core.net.HttpDownloader
import ch.lightspots.shunter.core.net.HttpStatusException
import ch.lightspots.shunter.core.obj
import ch.lightspots.shunter.core.parseJsonText
import ch.lightspots.shunter.core.str
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

private val logger = KotlinLogging.logger {}

/** A failed modwerkstatt.com request. [status] and [error] (the API's error code) are set when the server answered. */
open class ModwerkstattException(message: String, cause: Throwable? = null, val status: Int? = null, val error: String? = null) :
    IOException(message, cause)

/** modwerkstatt.com no longer accepts the session token: it expired or was signed out elsewhere. */
class ModwerkstattAuthException(message: String, cause: Throwable? = null, status: Int? = null, error: String? = null) :
    ModwerkstattException(message, cause, status, error)

/**
 * Sign-in to modwerkstatt.com, the way the ModWerkstatt ModManager does it: modwerkstatt.com mails
 * a code to the address ([requestCode]), which is exchanged for a session token ([confirmCode]).
 * The token is sent as `Authorization: Bearer` header, never in a URL.
 */
class ModwerkstattClient(
    private val http: HttpDownloader,
    private val apiUrl: String = DEFAULT_API_URL,
    private val clock: Clock = Clock.systemUTC(),
) {

    /** Asks modwerkstatt.com to mail a sign-in code to [email]. */
    suspend fun requestCode(email: String) {
        call(HttpMethod.Post, "/login/request", json = buildJsonObject { put("email", normalizeEmail(email)) }.toString())
        logger.info { "Requested a modwerkstatt.com sign-in code" }
    }

    /** Exchanges the mailed [code] for a session token. */
    suspend fun confirmCode(email: String, code: String, deviceName: String): ModwerkstattLogin {
        val address = normalizeEmail(email)
        val body = buildJsonObject {
            put("email", address)
            put("code", code.trim())
            put("device_name", deviceName)
        }
        val json = call(HttpMethod.Post, "/login/confirm", json = body.toString())
        val token = json["token"].str()?.takeIf { it.isNotBlank() } ?: throw ModwerkstattException("modwerkstatt.com sent no token")
        val user = json["user"]
        return ModwerkstattLogin(
            token = token,
            email = address,
            name = user["name"].str(),
            membershipUntil = user["membership_until"].str(),
            expiresAt = parseExpiry(json["expires_at"].str()),
        ).also { logger.info { "Signed in to modwerkstatt.com as ${it.name ?: "?"}" } }
    }

    /** [login] with the account details modwerkstatt.com has now. Throws [ModwerkstattAuthException] when the token is no longer valid. */
    suspend fun me(login: ModwerkstattLogin): ModwerkstattLogin {
        val json = call(HttpMethod.Get, "/me", token = login.token)
        // The account is either the whole answer or below "user"
        val user = json["user"].obj() ?: json
        return login.copy(
            name = user["name"].str() ?: login.name,
            membershipUntil = if (user.obj()?.containsKey("membership_until") ==
                true
            ) {
                user["membership_until"].str()
            } else {
                login.membershipUntil
            },
        )
    }

    /** Ends the session on modwerkstatt.com, so the token becomes invalid. */
    suspend fun logout(login: ModwerkstattLogin) {
        call(HttpMethod.Post, "/logout", token = login.token, json = "{}")
        logger.info { "Signed out of modwerkstatt.com" }
    }

    private suspend fun call(method: HttpMethod, path: String, token: String? = null, json: String? = null): JsonElement {
        val headers = buildMap {
            put("Accept", "application/json")
            token?.let { put("Authorization", "Bearer $it") }
        }
        val response = try {
            http.request(method, apiUrl.trimEnd('/') + path, headers, json = json)
        } catch (e: HttpStatusException) {
            throw toException(e, path)
        }
        if (response.text.isBlank()) return JsonNull
        return runCatching { parseJsonText(response.text) }
            .getOrElse { throw ModwerkstattException("modwerkstatt.com sent an unreadable answer for $path", it) }
    }

    private fun toException(e: HttpStatusException, path: String): ModwerkstattException {
        val error = e.body?.let { runCatching { parseJsonText(it)["error"].str() }.getOrNull() }
        logger.warn { "modwerkstatt.com $path: HTTP ${e.status}, error ${error ?: "?"}" }
        return when {
            error == "invalid_code" -> ModwerkstattException("The code is wrong or has expired.", e, e.status, error)

            error == "too_many_attempts" -> ModwerkstattException("Too many attempts, try again later.", e, e.status, error)

            error == "membership_required" ->
                ModwerkstattException("This needs an active modwerkstatt.com membership.", e, e.status, error)

            error == "unauthorized" || error == "token_invalid" || e.status == 401 ->
                ModwerkstattAuthException("modwerkstatt.com no longer accepts the sign-in.", e, e.status, error)

            e.status == 429 -> ModwerkstattException("modwerkstatt.com received too many requests, try again later.", e, e.status, error)

            else -> ModwerkstattException(
                "modwerkstatt.com answered HTTP ${e.status}${error?.let {
                    " ($it)"
                }.orEmpty()}",
                e,
                e.status,
                error,
            )
        }
    }

    /** `expires_at` as ISO date-time, with or without offset (then UTC). A missing or past one counts as [FALLBACK_LIFETIME]. */
    private fun parseExpiry(value: String?): Long {
        val now = clock.instant()
        val parsed = value?.trim()?.takeIf { it.isNotEmpty() }?.let {
            runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
                ?: runCatching { Instant.parse(it) }.getOrNull()
                ?: runCatching { LocalDateTime.parse(it.replace(' ', 'T').take(19)).toInstant(ZoneOffset.UTC) }.getOrNull()
        }
        return (parsed?.takeIf { it.isAfter(now) } ?: now.plus(FALLBACK_LIFETIME)).epochSecond
    }

    companion object {
        const val DEFAULT_API_URL = "https://modwerkstatt.com/api"

        /** How long a token counts as valid when modwerkstatt.com does not say, as in the ModWerkstatt ModManager. */
        val FALLBACK_LIFETIME: Duration = Duration.ofDays(30)

        /** Whether [url] goes to modwerkstatt.com over HTTPS, the only place the session token is sent to. */
        fun isModwerkstattUrl(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            val host = uri.host?.lowercase() ?: return false
            return uri.scheme.equals("https", ignoreCase = true) && (host == "modwerkstatt.com" || host.endsWith(".modwerkstatt.com"))
        }

        private fun normalizeEmail(email: String) = email.trim().lowercase()
    }
}
