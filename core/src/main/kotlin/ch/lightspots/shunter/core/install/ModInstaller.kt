package ch.lightspots.shunter.core.install

import ch.lightspots.shunter.core.archive.ArchiveExtractor
import ch.lightspots.shunter.core.mod.ModManifest
import ch.lightspots.shunter.core.mod.ModScanner
import ch.lightspots.shunter.core.paths.AppDirs
import ch.lightspots.shunter.core.readableMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.name

private val logger = KotlinLogging.logger {}

class InstallException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** One mod folder written by an install. [backup] holds the version it replaced, if any. */
data class InstalledFolder(val folderName: String, val modId: String, val revision: Int?, val target: Path, val backup: Path?)

/**
 * Installs mods from archives into a mods folder (normally `local/mods`).
 *
 * An install never deletes a mod: the previous version is moved to [AppDirs.backups] first and
 * restored if moving the new version in fails.
 */
@OptIn(ExperimentalPathApi::class)
class ModInstaller(
    private val appDirs: AppDirs,
    private val registry: InstallRegistry = InstallRegistry(appDirs.registryFile),
    private val clock: Clock = Clock.systemUTC(),
    /** Backups kept per mod folder; older ones are deleted after an install. */
    private val keepBackups: Int = 2,
) {

    fun install(
        archive: Path,
        modsDir: Path,
        origin: InstallOrigin = InstallOrigin(),
        archiveSha256: String? = null,
    ): List<InstalledFolder> {
        appDirs.ensureCreated()
        logger.info { "Installing $archive (sha256 ${archiveSha256 ?: "?"}, from ${origin.ref ?: "local archive"}) into $modsDir" }
        val work = appDirs.work.resolve("extract-${UUID.randomUUID()}")
        try {
            ArchiveExtractor.extract(archive, work)
            val mods = findMods(work)
            modsDir.createDirectories()
            return mods.map { (root, manifest, folderName) ->
                val installed = moveIntoPlace(root, modsDir.resolve(folderName), folderName, manifest)
                registry.put(
                    modsDir,
                    InstallRecord(
                        folderName = folderName,
                        modId = manifest.modId,
                        revision = manifest.revision,
                        origin = origin,
                        archiveSha256 = archiveSha256,
                        installedAt = clock.instant().epochSecond,
                    ),
                )
                pruneBackups(folderName)
                logger.info {
                    "Installed $folderName (mod id ${manifest.modId}, revision ${manifest.revision ?: "?"})" +
                        (installed.backup?.let { ", previous version moved to $it" } ?: "")
                }
                installed
            }
        } finally {
            if (work.exists()) work.deleteRecursively()
        }
    }

    /** Moves a mod out of [modsDir] into the backups and forgets it. Returns the backup location. */
    fun uninstall(modsDir: Path, folderName: String): Path {
        checkFolderName(folderName)
        val target = modsDir.resolve(folderName)
        if (!target.isDirectory()) throw InstallException("Not installed: $target")
        appDirs.ensureCreated()
        val backup = newBackupDir(folderName)
        moveDirectory(target, backup)
        registry.remove(modsDir, folderName)
        pruneBackups(folderName)
        logger.info { "Removed $folderName from $modsDir, moved to $backup" }
        return backup
    }

    private data class FoundMod(val root: Path, val manifest: ModManifest, val folderName: String)

    private fun findMods(extracted: Path): List<FoundMod> {
        val roots = ModScanner.findModRoots(extracted)
        if (roots.isEmpty()) throw InstallException("No mod found in archive (no ${ModManifest.FILE_NAME})")
        val mods = roots.map { root ->
            val manifest = runCatching { ModManifest.read(root.resolve(ModManifest.FILE_NAME)) }
                .getOrElse { throw InstallException("Broken ${ModManifest.FILE_NAME} in archive: ${it.readableMessage()}", it) }
            // mod.json directly at the archive top: the folder is named after the mod id
            val folderName = if (root == extracted) manifest.modId else root.name
            checkFolderName(folderName)
            FoundMod(root, manifest, folderName)
        }
        mods.groupBy { it.folderName }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            throw InstallException("Archive contains the mod folder '$it' more than once")
        }
        return mods
    }

    private fun moveIntoPlace(source: Path, target: Path, folderName: String, manifest: ModManifest): InstalledFolder {
        val backup = if (target.exists()) newBackupDir(folderName).also { moveDirectory(target, it) } else null
        try {
            moveDirectory(source, target)
        } catch (e: IOException) {
            if (backup != null) {
                logger.warn(e) { "Moving $folderName into place failed, restoring the previous version from $backup" }
                if (target.exists()) target.deleteRecursively()
                moveDirectory(backup, target)
            }
            throw InstallException("Failed to install $folderName: ${e.readableMessage()}", e)
        }
        return InstalledFolder(folderName, manifest.modId, manifest.revision, target, backup)
    }

    private fun newBackupDir(folderName: String): Path {
        val base = appDirs.backups.resolve(folderName)
        val stamp = BACKUP_STAMP.format(clock.instant())
        // Two installs within the same second must not collide
        return generateSequence(0) { it + 1 }
            .map { if (it == 0) base.resolve(stamp) else base.resolve("$stamp-$it") }
            .first { !it.exists() }
    }

    private fun pruneBackups(folderName: String) {
        val dir = appDirs.backups.resolve(folderName)
        if (!dir.isDirectory()) return
        dir.listDirectoryEntries()
            .sortedByDescending { it.name }
            .drop(keepBackups)
            .forEach {
                logger.debug { "Deleting old backup $it" }
                it.deleteRecursively()
            }
    }

    /** Renames when possible; copies and deletes when source and target are on different disks. */
    private fun moveDirectory(source: Path, target: Path) {
        target.parent.createDirectories()
        try {
            source.moveTo(target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            try {
                source.copyToRecursively(target, followLinks = false, overwrite = false)
            } catch (e: IOException) {
                if (target.exists()) target.deleteRecursively()
                throw e
            }
            source.deleteRecursively()
        }
    }

    private fun checkFolderName(name: String) {
        if (!SAFE_FOLDER_NAME.matches(name) || name == "." || name == "..") {
            throw InstallException("Refusing unsafe mod folder name: '$name'")
        }
    }

    private companion object {
        val SAFE_FOLDER_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._ -]*")
        val BACKUP_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}
