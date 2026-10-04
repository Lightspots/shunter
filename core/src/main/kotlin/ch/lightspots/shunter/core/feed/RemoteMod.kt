package ch.lightspots.shunter.core.feed

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A site that publishes a machine-readable list of mods. Stored in the install registry by [id]. */
@Serializable
enum class FeedSource(val id: String, val label: String, val url: String) {
    @SerialName("tfnet")
    TFNET("tfnet", "transportfever.net", "https://www.transportfever.net/filebase/repos/tpf3-v3.json"),

    @SerialName("modwerkstatt")
    MODWERKSTATT("modwerkstatt", "modwerkstatt.com", "https://modwerkstatt.com/tpfmm/"),
    ;

    companion object {
        fun byId(id: String): FeedSource? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** A downloadable archive. Size and checksum are verified when the feed provides them. */
data class RemoteFile(
    val id: String,
    val fileName: String,
    val downloadUrl: String,
    val size: Long? = null,
    val sha256: String? = null,
    /** Epoch seconds. */
    val changedAt: Long? = null,
    /** Mod folder inside the archive, if the feed knows it. */
    val folderName: String? = null,
)

data class RemoteDependency(
    val name: String?,
    /** Entry id in the same feed, if the dependency could be resolved there. */
    val remoteId: String?,
    val required: Boolean,
    val pageUrl: String? = null,
    /** File to install, as suggested by the feed. */
    val file: RemoteFile? = null,
)

/** A mod entry on a site. Installing it means installing all of [files]. */
data class RemoteMod(
    val source: FeedSource,
    val id: String,
    val name: String,
    val author: String? = null,
    val version: String? = null,
    val pageUrl: String? = null,
    /** Epoch seconds. */
    val updatedAt: Long? = null,
    val files: List<RemoteFile> = emptyList(),
    val dependencies: List<RemoteDependency> = emptyList(),
    val tags: List<String> = emptyList(),
) {
    /** `tfnet:8126` style reference used by the CLI. */
    val ref: String get() = "${source.id}:$id"
}
