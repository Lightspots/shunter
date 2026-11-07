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
            imageUrl = (json["images"]["preview"].str() ?: json["images"]["large"].str())?.takeIf { it.isNotBlank() },
        )
    }

    private fun version(json: JsonElement): String? = json["custom_fields"].obj()?.values
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
 * modwerkstatt.com `json/mwmanager`, the list the ModWerkstatt ModManager reads: an array of TF3 mods
 * with one archive each. `revision` is the revision in the mod's `mod.json`. There are no checksums,
 * change dates or authors; required mods point to other entries by `id`.
 */
object ModwerkstattFeedParser {
    fun parse(text: String): List<RemoteMod> {
        val json = parseJsonText(text) as? JsonArray ?: throw FeedFormatException("Not a modwerkstatt mod list")
        val titles = json.mapNotNull { entry ->
            entry["id"].long()?.let { it.toString() to (entry["title"].str() ?: entry["modId"].str()) }
        }
            .toMap()
        return json.mapNotNull { parseEntry(it, titles) }
    }

    private fun parseEntry(json: JsonElement, titles: Map<String, String?>): RemoteMod? {
        val id = json["id"].long()?.toString() ?: return null
        val revision = json["revision"].str()?.trim()?.takeIf { it.isNotEmpty() }
        val file = json["download_url"].str()?.takeIf { it.isNotBlank() }?.let { url ->
            RemoteFile(
                // A new revision is a new file, which is how updates are found
                id = revision?.let { "$id-r$it" } ?: id,
                fileName = url.substringBefore('?').substringAfterLast('/').ifEmpty { "$id.zip" },
                downloadUrl = url,
                size = json["file_size"].long()?.takeIf { it > 0 },
            )
        }
        return RemoteMod(
            source = FeedSource.MODWERKSTATT,
            id = id,
            name = json["title"].str() ?: json["modId"].str() ?: "Entry $id",
            version = revision?.let { "rev $it" },
            files = listOfNotNull(file),
            dependencies = json["required_mods"].arr().map { dep ->
                val depId = dep["id"].long()?.toString()?.takeIf { it in titles }
                RemoteDependency(name = depId?.let { titles[it] } ?: dep["modId"].str(), remoteId = depId, required = true)
            },
            tags = json["categories"].arr().mapNotNull { it["name"].str() },
            imageUrl = json["image_url"].str()?.takeIf { it.isNotBlank() },
        )
    }
}
