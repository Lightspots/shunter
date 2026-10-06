package ch.lightspots.shunter.core.modio

import ch.lightspots.shunter.core.arr
import ch.lightspots.shunter.core.get
import ch.lightspots.shunter.core.int
import ch.lightspots.shunter.core.long
import ch.lightspots.shunter.core.parseJsonText
import ch.lightspots.shunter.core.str
import kotlinx.serialization.json.JsonElement

/** A mod on mod.io. The game stores it in the mod.io folder under [folderName], its id. */
data class ModIoMod(
    val id: Long,
    val name: String,
    val summary: String? = null,
    val author: String? = null,
    val profileUrl: String? = null,
    /** Epoch seconds. */
    val updatedAt: Long? = null,
    /** Version of the current file, as entered by the author. */
    val version: String? = null,
    /** Size of the current file's archive. */
    val fileSize: Long? = null,
    val subscribers: Long? = null,
    val tags: List<String> = emptyList(),
) {
    val folderName: String get() = id.toString()
}

data class ModIoUser(val id: Long, val username: String)

/** One page of a list; [total] counts all matches, not only [mods]. */
data class ModIoPage(val mods: List<ModIoMod>, val offset: Int, val total: Int)

/** Reads the JSON objects of the mod.io REST API (v1). */
internal object ModIoParser {

    fun user(json: JsonElement): ModIoUser {
        val id = json["id"].long() ?: throw ModIoException("mod.io returned no user")
        return ModIoUser(id, json["username"].str() ?: json["name_id"].str() ?: "user $id")
    }

    fun page(json: JsonElement): ModIoPage = ModIoPage(
        mods = json["data"].arr().mapNotNull(::mod),
        offset = json["result_offset"].int() ?: 0,
        total = json["result_total"].int() ?: 0,
    )

    fun mod(json: JsonElement): ModIoMod? {
        val id = json["id"].long() ?: return null
        val file = json["modfile"]
        return ModIoMod(
            id = id,
            name = json["name"].str() ?: "mod.io $id",
            summary = json["summary"].str(),
            author = json["submitted_by"]["username"].str(),
            profileUrl = json["profile_url"].str(),
            updatedAt = json["date_updated"].long(),
            version = file["version"].str()?.trim()?.takeIf { it.isNotEmpty() },
            fileSize = file["filesize"].long(),
            subscribers = json["stats"]["subscribers_total"].long(),
            tags = json["tags"].arr().mapNotNull { it["name"].str() },
        )
    }

    /** The `message` of a mod.io error response (`{"error": {"code": 401, "message": …}}`), if [body] is one. */
    fun errorMessage(body: String?): String? {
        val json = body?.let { runCatching { parseJsonText(it) }.getOrNull() } ?: return null
        return json["error"]["message"].str()?.takeIf { it.isNotBlank() }
    }
}
