package ch.lightspots.shunter.core.feed

import ch.lightspots.shunter.core.arr
import ch.lightspots.shunter.core.bool
import ch.lightspots.shunter.core.get
import ch.lightspots.shunter.core.long
import ch.lightspots.shunter.core.obj
import ch.lightspots.shunter.core.parseJsonText
import ch.lightspots.shunter.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

class FeedFormatException(message: String) : RuntimeException(message)

/**
 * transportfever.net filebase repository, format "TransportFeverNetRepo" version 3.
 * Each entry has a `latest_file` with size and sha256, and dependencies that point to other entries.
 */
object TfnetFeedParser {
    /** Labels of the custom field holding the version, which has no fixed key. */
    private val VERSION_LABELS = setOf("aktuelle version", "current version", "version")

    fun parse(text: String): List<RemoteMod> {
        val json = parseJsonText(text)
        val format = json["repo"]["format"].str()
        if (format != "TransportFeverNetRepo") throw FeedFormatException("Not a transportfever.net repository (format: $format)")
        return json["files"].arr().mapNotNull(::parseEntry)
    }

    private fun parseEntry(json: JsonElement): RemoteMod? {
        val id = json["entry_id"].long()?.toString() ?: return null
        val latest = parseFile(json["latest_file"])
        return RemoteMod(
            source = FeedSource.TFNET,
            id = id,
            name = json["name"].str() ?: "Entry $id",
            author = json["author"]["name"].str(),
            version = version(json),
            pageUrl = json["entry_url"].str(),
            updatedAt = json["updated"].long(),
            files = listOfNotNull(latest),
            dependencies = json["dependencies"].arr().map(::parseDependency),
            tags = json["tags"].arr().mapNotNull { it.str() },
        )
    }

    private fun version(json: JsonElement): String? =
        json["custom_fields"].obj()?.values
            ?.firstOrNull { it["label"].str()?.trim()?.lowercase() in VERSION_LABELS }
            ?.get("value").str()?.trim()?.takeIf { it.isNotEmpty() }

    private fun parseFile(json: JsonElement?): RemoteFile? {
        val id = json["file_id"].long()?.toString() ?: return null
        val url = json["download"].str() ?: return null
        return RemoteFile(
            id = id,
            fileName = json["filename"].str() ?: "file-$id",
            downloadUrl = url,
            size = json["size"].long(),
            sha256 = json["sha256"].str()?.lowercase()?.takeIf { it.isNotBlank() },
            changedAt = json["changed"].long(),
        )
    }

    private fun parseDependency(json: JsonElement) = RemoteDependency(
        name = json["name"].str(),
        remoteId = json["entry_id"].long()?.toString()?.takeIf { json["type"].str() == "filebase" },
        required = json["required"].bool() ?: true,
        pageUrl = json["entry_url"].str() ?: json["raw"].str(),
        file = parseFile(json["install"]),
    )
}

/**
 * modwerkstatt.com `tpfmm` repository, the format eis_os' CommonAPI2 reads for TPF2.
 * One entry can contain several files, each being its own mod folder.
 */
object ModwerkstattFeedParser {
    /** As of 2026-10 the feed only lists "tpf2"; TF3 mods are expected to appear as "tpf3". */
    const val TF3_GAME = "tpf3"

    fun parse(text: String, game: String = TF3_GAME): List<RemoteMod> {
        val json = parseJsonText(text)
        if (json["repo_info"].obj() == null || json["mods"] !is JsonArray) {
            throw FeedFormatException("Not a modwerkstatt repository")
        }
        val entryBase = json["mod_base_url"].str().orEmpty()
        val fileBase = json["file_base_url"].str().orEmpty()
        return json["mods"].arr()
            .filter { it["game"].str().equals(game, ignoreCase = true) }
            .mapNotNull { parseEntry(it, entryBase, fileBase) }
    }

    private fun parseEntry(json: JsonElement, entryBase: String, fileBase: String): RemoteMod? {
        val id = json["id"].long()?.toString() ?: return null
        return RemoteMod(
            source = FeedSource.MODWERKSTATT,
            id = id,
            name = json["name"].str() ?: "Entry $id",
            author = json["author"].str(),
            version = json["version"].str(),
            pageUrl = json["url"].str()?.let { resolve(entryBase, it) },
            updatedAt = json["timechanged"].long(),
            files = json["files"].arr().mapNotNull { file ->
                val url = file["url"].str() ?: return@mapNotNull null
                RemoteFile(
                    id = file["fileid"].str() ?: url,
                    fileName = file["filename"].str() ?: url.substringAfterLast('/'),
                    downloadUrl = resolve(fileBase, url),
                    changedAt = file["timechanged"].long(),
                    folderName = file["foldername"].str(),
                )
            },
            tags = json["tags"].arr().mapNotNull { it.str() },
        )
    }

    private fun resolve(base: String, path: String) =
        if (path.startsWith("http://") || path.startsWith("https://")) path else base + path
}
