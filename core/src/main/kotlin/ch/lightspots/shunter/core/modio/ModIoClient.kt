package ch.lightspots.shunter.core.modio

import ch.lightspots.shunter.core.net.HttpDownloader
import ch.lightspots.shunter.core.net.HttpStatusException
import ch.lightspots.shunter.core.parseJsonText
import ch.lightspots.shunter.core.paths.GamePathDetector
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.net.URLEncoder

private val logger = KotlinLogging.logger {}

/** A failed mod.io request. [status] and [errorRef] (mod.io's error code) are set when mod.io answered. */
open class ModIoException(message: String, cause: Throwable? = null, val status: Int? = null, val errorRef: Int? = null) :
    IOException(message, cause)

/** mod.io refused the access token: it expired, was revoked or mistyped, or lacks write access for a change. */
class ModIoAuthException(message: String, cause: Throwable? = null) : ModIoException(message, cause, 401)

/** Result of [ModIoClient.subscribe]. */
data class ModIoSubscribeResult(val mod: ModIoMod, val alreadySubscribed: Boolean)

/**
 * Reads the TF3 catalog and the user's subscriptions from the mod.io REST API, and changes subscriptions.
 *
 * Authenticates with an OAuth access token in the `Authorization` header only. mod.io's API key would
 * have to be a query parameter, and URLs end up in logs and error messages.
 */
class ModIoClient(
    private val http: HttpDownloader,
    private val login: ModIoLogin,
    private val apiUrl: String = DEFAULT_API_URL,
    /** Language for names and summaries, if the author translated them (ISO 639, e.g. `de`). */
    private val language: String? = null,
) {

    /** The account the token belongs to. */
    suspend fun me(): ModIoUser = ModIoParser.user(get("/me"))

    /** TF3 mods matching [query] (all when null), most recently updated first. */
    suspend fun searchMods(query: String? = null, limit: Int = 50, offset: Int = 0): ModIoPage {
        val params = buildList {
            query?.trim()?.takeIf { it.isNotEmpty() }?.let { add("_q" to it) }
            add("_sort" to "-date_updated")
            add("_limit" to limit.coerceIn(1, MAX_LIMIT).toString())
            add("_offset" to offset.toString())
        }
        return ModIoParser.page(get("/games/$GAME_ID/mods", params))
    }

    /**
     * A TF3 mod by its id, its name id, or its mod.io address (`https://mod.io/g/<game>/m/<name id>`).
     * Null when mod.io has no such mod for TF3.
     */
    suspend fun findMod(ref: String): ModIoMod? {
        val key = ref.trim().substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast("/m/").substringBefore('/')
        if (key.isEmpty()) return null
        val id = key.toLongOrNull()
        if (id == null) {
            return ModIoParser.page(get("/games/$GAME_ID/mods", listOf("name_id" to key, "_limit" to "1"))).mods.firstOrNull()
        }
        return try {
            ModIoParser.mod(get("/games/$GAME_ID/mods/$id"))
        } catch (e: ModIoException) {
            if (e.status == 404) null else throw e
        }
    }

    /** Mods [modId] depends on, including theirs (mod.io follows them up to five levels deep). */
    suspend fun dependencies(modId: Long): List<ModIoMod> =
        ModIoParser.page(get("/games/$GAME_ID/mods/$modId/dependencies", listOf("recursive" to "true"))).mods
            .filter { it.id != modId }
            .distinctBy { it.id }

    /** Subscribes the user to [modId] and, with [includeDependencies], to everything it depends on. */
    suspend fun subscribe(modId: Long, includeDependencies: Boolean = true): ModIoSubscribeResult {
        val (status, json) = call(
            HttpMethod.Post,
            "/games/$GAME_ID/mods/$modId/subscribe",
            form = mapOf("include_dependencies" to includeDependencies.toString()),
        )
        val mod = json?.let(ModIoParser::mod) ?: throw ModIoException("mod.io sent no mod for the subscription to $modId")
        logger.info { "Subscribed to mod.io mod $modId (HTTP $status, dependencies included: $includeDependencies)" }
        // 201 for a new subscription, 200 when it existed already
        return ModIoSubscribeResult(mod, alreadySubscribed = status == 200)
    }

    /** Ends the subscription to [modId]. Returns false when the user was not subscribed. */
    suspend fun unsubscribe(modId: Long): Boolean {
        try {
            call(HttpMethod.Delete, "/games/$GAME_ID/mods/$modId/subscribe")
        } catch (e: ModIoException) {
            if (e.errorRef == NOT_SUBSCRIBED) return false
            throw e
        }
        logger.info { "Unsubscribed from mod.io mod $modId" }
        return true
    }

    /** All TF3 mods the user is subscribed to. */
    suspend fun subscribedMods(): List<ModIoMod> {
        val mods = mutableListOf<ModIoMod>()
        // A cap on the pages, so a server that keeps answering with the same page cannot keep us here
        repeat(MAX_PAGES) {
            val params = listOf("game_id" to GAME_ID, "_limit" to MAX_LIMIT.toString(), "_offset" to mods.size.toString())
            val page = ModIoParser.page(get("/me/subscribed", params))
            mods += page.mods
            if (page.mods.isEmpty() || mods.size >= page.total) return mods.distinctBy { it.id }
        }
        logger.warn { "mod.io subscriptions: stopped after $MAX_PAGES pages, ${mods.size} mods" }
        return mods.distinctBy { it.id }
    }

    private suspend fun get(path: String, params: List<Pair<String, String>> = emptyList()): JsonElement =
        call(HttpMethod.Get, path, params).second ?: throw ModIoException("mod.io sent an empty answer for $path")

    /** Returns the status and the parsed body, null when there is none (204). */
    private suspend fun call(
        method: HttpMethod,
        path: String,
        params: List<Pair<String, String>> = emptyList(),
        form: Map<String, String>? = null,
    ): Pair<Int, JsonElement?> {
        val query = params.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" }
        val url = apiUrl.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
        val headers = buildMap {
            put("Authorization", "Bearer ${login.accessToken}")
            put("Accept", "application/json")
            language?.let { put("Accept-Language", it) }
        }
        val response = try {
            // mod.io wants the form content type on every change, also on a DELETE without parameters (else HTTP 415)
            http.request(method, url, headers, form ?: emptyMap<String, String>().takeIf { method != HttpMethod.Get })
        } catch (e: HttpStatusException) {
            throw toModIoException(e, method, path)
        }
        if (response.text.isBlank()) return response.status to null
        val json = runCatching { parseJsonText(response.text) }
            .getOrElse { throw ModIoException("mod.io sent an unreadable answer for $path", it) }
        return response.status to json
    }

    private fun toModIoException(e: HttpStatusException, method: HttpMethod, path: String): ModIoException {
        val error = ModIoParser.error(e.body)
        val reason = error?.message?.let { " ($it)" }.orEmpty()
        return when {
            e.status == 401 && method == HttpMethod.Get ->
                ModIoAuthException("mod.io did not accept the access token, it may have expired or been revoked$reason", e)

            e.status == 401 ->
                ModIoAuthException("mod.io refused the change: the access token may be read-only, expired or revoked$reason", e)

            else -> ModIoException(
                "mod.io request failed (HTTP ${e.status} for ${method.value} $path)" + error?.message?.let { ": $it" }.orEmpty(),
                e,
                e.status,
                error?.ref,
            )
        }
    }

    companion object {
        const val GAME_ID = GamePathDetector.MOD_IO_GAME_ID

        /** mod.io moved from `api.mod.io` to per-game hosts. */
        const val DEFAULT_API_URL = "https://g-$GAME_ID.modapi.io/v1"

        /** Where users create a personal access token. */
        const val ACCESS_PAGE = "https://mod.io/me/access"

        private const val MAX_LIMIT = 100
        private const val MAX_PAGES = 50

        /** mod.io error_ref: "The requested user is not currently subscribed to the requested mod." */
        private const val NOT_SUBSCRIBED = 15005
    }
}
