package ch.lightspots.shunter.core

import ch.lightspots.shunter.core.feed.FeedResult
import ch.lightspots.shunter.core.feed.FeedSource
import ch.lightspots.shunter.core.feed.RemoteFile
import ch.lightspots.shunter.core.feed.RemoteMod
import ch.lightspots.shunter.core.install.InstallOrigin
import ch.lightspots.shunter.core.install.InstallRecord
import ch.lightspots.shunter.core.install.InstallRegistry
import ch.lightspots.shunter.core.install.InstalledFolder
import ch.lightspots.shunter.core.install.ModInstaller
import ch.lightspots.shunter.core.mod.InstalledMod
import ch.lightspots.shunter.core.mod.ModLocation
import ch.lightspots.shunter.core.mod.ModReference
import ch.lightspots.shunter.core.mod.ModScanner
import ch.lightspots.shunter.core.net.DownloadProgress
import ch.lightspots.shunter.core.net.HttpDownloader
import ch.lightspots.shunter.core.paths.AppDirs
import ch.lightspots.shunter.core.paths.GamePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.isDirectory

/** A mod we installed from a feed, with a newer file available there. */
data class AvailableUpdate(
    val record: InstallRecord,
    val remote: RemoteMod,
    val file: RemoteFile,
)

/**
 * A mod installed with shunter whose folder no longer matches the install record, because it was
 * replaced or removed by hand. Which version is installed is unknown, so no update is offered.
 */
data class ChangedOutside(
    val record: InstallRecord,
    /** What is in the folder now; null when the folder is gone. */
    val current: InstalledMod?,
)

/** A `mod.json` dependency that no installed mod satisfies. */
data class MissingDependency(
    val mod: InstalledMod,
    val dependency: ModReference,
    val displayName: String?,
    /** An installed mod with that id exists, but its revision is outside the allowed range. */
    val wrongRevision: Int? = null,
)

/**
 * Entry point for frontends (CLI, UI): everything they need, without knowing the parts.
 * Suspend functions move their blocking work to [Dispatchers.IO]; the others block the caller.
 */
class ModManager(
    val gamePaths: GamePaths,
    val appDirs: AppDirs = AppDirs.fromEnvironment(),
    private val http: HttpDownloader = HttpDownloader(),
    val registry: InstallRegistry = InstallRegistry(appDirs.registryFile),
    private val installer: ModInstaller = ModInstaller(appDirs, registry),
) {

    /** Where installs go. */
    val modsDir: Path
        get() = gamePaths.localMods ?: throw IllegalStateException(
            "TF3 mod folder not found. Start the game once, or set the folder explicitly.",
        )

    fun scan(locations: Collection<ModLocation> = ModLocation.entries): List<InstalledMod> =
        locations.flatMap { location -> gamePaths.dirFor(location)?.let { ModScanner.scan(it, location) }.orEmpty() }

    fun installArchive(archive: Path): List<InstalledFolder> =
        installer.install(archive, modsDir, archiveSha256 = HttpDownloader.sha256(archive))

    /** Downloads and installs every file of [mod]. [progress] receives the file being downloaded. */
    suspend fun installRemote(mod: RemoteMod, progress: ((RemoteFile) -> DownloadProgress)? = null): List<InstalledFolder> {
        require(mod.files.isNotEmpty()) { "${mod.ref} has no downloadable file" }
        return mod.files.flatMap { file -> installRemoteFile(mod, file, progress?.invoke(file)) }
    }

    suspend fun installRemoteFile(
        mod: RemoteMod,
        file: RemoteFile,
        progress: DownloadProgress? = null,
    ): List<InstalledFolder> = withContext(Dispatchers.IO) {
        val archive = appDirs.ensureCreated().downloads.resolve(downloadFileName(mod, file))
        val sha256 = http.download(file.downloadUrl, archive, file.size, file.sha256, progress)
        val origin = InstallOrigin(
            source = mod.source,
            remoteId = mod.id,
            fileId = file.id,
            fileName = file.fileName,
            remoteChangedAt = file.changedAt,
            version = mod.version,
        )
        installer.install(archive, modsDir, origin, sha256)
    }

    fun uninstall(folderName: String): Path = installer.uninstall(modsDir, folderName)

    /** Mods installed from a feed whose entry now offers a different file. Skips mods in [changedOutside]. */
    fun availableUpdates(feeds: List<FeedResult>): List<AvailableUpdate> {
        val remoteByRef = feeds.flatMap { it.mods }.associateBy { it.ref }
        val changed = changedOutside().map { it.record.folderName }.toSet()
        return registry.records(modsDir).values.filter { it.folderName !in changed }.mapNotNull { record ->
            val origin = record.origin
            val remote = remoteByRef[origin.ref] ?: return@mapNotNull null
            // modwerkstatt entries can hold several mod folders; match ours by name
            val file = remote.files.firstOrNull { it.folderName == record.folderName } ?: remote.files.singleOrNull()
                ?: return@mapNotNull null
            val newer = if (file.sha256 != null && record.archiveSha256 != null) {
                // transportfever.net has checksums: a re-upload of the same archive is no update
                !file.sha256.equals(record.archiveSha256, ignoreCase = true)
            } else {
                file.id != origin.fileId ||
                    (file.changedAt != null && origin.remoteChangedAt != null && file.changedAt > origin.remoteChangedAt)
            }
            if (newer) AvailableUpdate(record, remote, file) else null
        }
    }

    /** Mods installed with shunter whose folder now holds a different mod id or revision, or is gone. */
    fun changedOutside(): List<ChangedOutside> = registry.records(modsDir).values.mapNotNull { record ->
        val folder = modsDir.resolve(record.folderName)
        if (!folder.isDirectory()) return@mapNotNull ChangedOutside(record, null)
        val current = ModScanner.read(folder, ModLocation.LOCAL)
        if (current.modId == record.modId && current.manifest?.revision == record.revision) null else ChangedOutside(record, current)
    }

    /** Required `mod.json` dependencies of [mods] that no mod in [available] satisfies. */
    fun missingDependencies(mods: List<InstalledMod>, available: List<InstalledMod> = scan()): List<MissingDependency> {
        val revisionsById = available.mapNotNull { m -> m.manifest?.let { it.modId to (it.revision ?: 0) } }
            .groupBy({ it.first }, { it.second })
        return mods.flatMap { mod ->
            mod.manifest?.dependencies.orEmpty()
                .filter { !it.optional }
                .mapNotNull { dep ->
                    val revisions = revisionsById[dep.mod.modId]
                    when {
                        revisions == null -> MissingDependency(mod, dep.mod, dep.displayName)
                        revisions.none { dep.mod.matches(it) } -> MissingDependency(mod, dep.mod, dep.displayName, revisions.first())
                        else -> null
                    }
                }
        }
    }

    /** Mod ids present in more than one folder; the game would see them twice. */
    fun duplicateModIds(mods: List<InstalledMod> = scan()): Map<String, List<InstalledMod>> =
        mods.filter { it.modId != null }.groupBy { it.modId!! }.filterValues { it.size > 1 }

    private fun downloadFileName(mod: RemoteMod, file: RemoteFile): String {
        val safeName = file.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "${mod.source.id}-${mod.id}-${file.id}-$safeName"
    }

    companion object {
        fun findRemote(feeds: List<FeedResult>, source: FeedSource, id: String): RemoteMod? =
            feeds.firstOrNull { it.source == source }?.mods?.firstOrNull { it.id == id }
    }
}
