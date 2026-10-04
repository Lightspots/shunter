package ch.lightspots.shunter.core.install

import ch.lightspots.shunter.core.feed.FeedSource
import ch.lightspots.shunter.core.stateJson
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Where an installed mod came from, so updates can be detected later. */
@Serializable
data class InstallOrigin(
    /** Null for a local archive. */
    val source: FeedSource? = null,
    /** Entry id in that feed. */
    val remoteId: String? = null,
    val fileId: String? = null,
    val fileName: String? = null,
    /** Change time of the downloaded file in the feed, epoch seconds. */
    val remoteChangedAt: Long? = null,
    val version: String? = null,
) {
    /** `tfnet:8126` style reference, like [ch.lightspots.shunter.core.feed.RemoteMod.ref]. Null for a local archive. */
    val ref: String? get() = source?.let { "${it.id}:$remoteId" }
}

@Serializable
data class InstallRecord(
    val folderName: String,
    val modId: String,
    val revision: Int? = null,
    val origin: InstallOrigin = InstallOrigin(),
    val archiveSha256: String? = null,
    /** Epoch seconds. */
    val installedAt: Long,
)

@Serializable
private data class RegistryFile(
    val version: Int = 1,
    /** Mods directory (absolute path) → folder name → record. Keyed by directory so test installs stay separate. */
    val modsDirs: Map<String, Map<String, InstallRecord>> = emptyMap(),
)

/** Remembers which mods we installed. Stored as JSON in our data folder, never inside the game folders. */
class InstallRegistry(private val file: Path) {

    fun records(modsDir: Path): Map<String, InstallRecord> = load().modsDirs[key(modsDir)].orEmpty()

    fun get(modsDir: Path, folderName: String): InstallRecord? = records(modsDir)[folderName]

    fun put(modsDir: Path, record: InstallRecord) = update(modsDir) { it + (record.folderName to record) }

    fun remove(modsDir: Path, folderName: String) = update(modsDir) { it - folderName }

    private fun update(modsDir: Path, change: (Map<String, InstallRecord>) -> Map<String, InstallRecord>) {
        val current = load()
        val dirKey = key(modsDir)
        val updated = current.copy(modsDirs = current.modsDirs + (dirKey to change(current.modsDirs[dirKey].orEmpty())))
        file.parent.createDirectories()
        // Write to a temp file first so a crash never leaves a half-written registry
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        tmp.writeText(stateJson.encodeToString(RegistryFile.serializer(), updated))
        tmp.moveTo(file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun load(): RegistryFile =
        if (file.exists()) stateJson.decodeFromString(RegistryFile.serializer(), file.readText()) else RegistryFile()

    private fun key(modsDir: Path) = modsDir.toAbsolutePath().normalize().toString()
}
