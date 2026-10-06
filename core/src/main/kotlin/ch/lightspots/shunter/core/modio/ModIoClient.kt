package ch.lightspots.shunter.core.modio

import ch.lightspots.shunter.core.net.HttpDownloader
import ch.lightspots.shunter.core.net.HttpStatusException
import ch.lightspots.shunter.core.parseJsonText
import ch.lightspots.shunter.core.paths.GamePathDetector
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.net.URLEncoder

private val logger = KotlinLogging.logger {}

open class ModIoException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** mod.io refused the access token: it expired, was revoked, or was mistyped. */
class ModIoAuthException(message: String, cause: Throwable? = null) : ModIoException(message, cause)

/**
 * Reads the TF3 catalog and the user's subscriptions from the mod.io REST API.
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

    private suspend fun get(path: String, params: List<Pair<String, String>> = emptyList()): JsonElement {
        val query = params.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" }
        val url = apiUrl.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
        val headers = buildMap {
            put("Authorization", "Bearer ${login.accessToken}")
            put("Accept", "application/json")
            language?.let { put("Accept-Language", it) }
        }
        val text = try {
            http.fetchText(url, headers)
        } catch (e: HttpStatusException) {
            val reason = ModIoParser.errorMessage(e.body)
            if (e.status == 401) {
                throw ModIoAuthException(
                    "mod.io did not accept the access token, it may have expired or been revoked" + reason.inBraces(),
                    e,
                )
            }
            throw ModIoException("mod.io request failed (HTTP ${e.status} for $path)" + reason.colon(), e)
        }
        return runCatching { parseJsonText(text) }.getOrElse { throw ModIoException("mod.io sent an unreadable answer for $path", it) }
    }

    private fun String?.inBraces() = this?.let { " ($it)" }.orEmpty()

    private fun String?.colon() = this?.let { ": $it" }.orEmpty()

    companion object {
        const val GAME_ID = GamePathDetector.MOD_IO_GAME_ID

        /** mod.io moved from `api.mod.io` to per-game hosts. */
        const val DEFAULT_API_URL = "https://g-$GAME_ID.modapi.io/v1"

        /** Where users create a personal access token. */
        const val ACCESS_PAGE = "https://mod.io/me/access"

        private const val MAX_LIMIT = 100
        private const val MAX_PAGES = 50
    }
}
