package ch.lightspots.shunter.core.mod

import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Reads mod folders from disk. Never writes anything. */
object ModScanner {

    /** All mod folders directly inside [dir], sorted by folder name. A missing [dir] yields an empty list. */
    fun scan(dir: Path, location: ModLocation): List<InstalledMod> {
        if (!dir.isDirectory()) return emptyList()
        return dir.listDirectoryEntries()
            .filter { it.isDirectory() && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
            .map { read(it, location) }
    }

    fun read(folder: Path, location: ModLocation): InstalledMod {
        val problems = mutableListOf<String>()

        val manifestFile = folder.resolve(ModManifest.FILE_NAME)
        val manifest = if (manifestFile.isRegularFile()) {
            runCatching { ModManifest.read(manifestFile) }
                .onFailure { problems += "Broken ${ModManifest.FILE_NAME}: ${it.message}" }
                .getOrNull()
        } else {
            problems += "No ${ModManifest.FILE_NAME}"
            null
        }

        val metadataFile = folder.resolve(ModMetadata.DIR_NAME).resolve(ModMetadata.FILE_NAME)
        val metadata = if (metadataFile.isRegularFile()) {
            runCatching { ModMetadata.read(metadataFile) }
                .onFailure { problems += "Broken ${ModMetadata.DIR_NAME}/${ModMetadata.FILE_NAME}: ${it.message}" }
                .getOrNull()
        } else {
            null
        }

        return InstalledMod(location, folder, manifest, metadata, problems)
    }

    /** True if [dir] looks like the root of a mod. */
    fun isModRoot(dir: Path): Boolean = dir.resolve(ModManifest.FILE_NAME).isRegularFile()

    /**
     * Finds mod roots in an extracted archive: the topmost folders containing a `mod.json`.
     * Archives usually hold `<modId>/mod.json`, but may wrap it in extra folders, contain several
     * mods, or have `mod.json` directly at the top.
     */
    fun findModRoots(dir: Path, maxDepth: Int = 4): List<Path> {
        if (isModRoot(dir)) return listOf(dir)
        if (maxDepth == 0 || !dir.isDirectory()) return emptyList()
        return dir.listDirectoryEntries()
            .filter { it.isDirectory() }
            .sortedBy { it.name }
            .flatMap { findModRoots(it, maxDepth - 1) }
    }
}
