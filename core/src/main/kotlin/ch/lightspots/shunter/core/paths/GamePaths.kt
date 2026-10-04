package ch.lightspots.shunter.core.paths

import ch.lightspots.shunter.core.mod.ModLocation
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** The folders Transport Fever 3 reads mods from. Null means "not found". */
data class GamePaths(
    /** `userdata/<id>/3493540/local`, the parent of [localMods] and [stagingArea]. */
    val userLocalDir: Path?,
    val localMods: Path?,
    val stagingArea: Path?,
    val modIoMods: Path?,
) {
    fun dirFor(location: ModLocation): Path? = when (location) {
        ModLocation.LOCAL -> localMods
        ModLocation.STAGING -> stagingArea
        ModLocation.MOD_IO -> modIoMods
    }
}

/** A `userdata/<steamAccountId>/3493540/local` folder found in a Steam installation. */
data class SteamUserCandidate(val steamRoot: Path, val accountId: String, val localDir: Path)

/**
 * Finds the native Linux TF3 folders. The game runs natively (no Proton), so everything is in the
 * user's home: Steam userdata for manual mods, `~/mod.io/common/10640` for Mod Hub subscriptions.
 */
class GamePathDetector(private val home: Path = Path(System.getProperty("user.home"))) {

    /** Steam installations that exist, deduplicated (`~/.steam/steam` is usually a link to one of the others). */
    fun steamRoots(): List<Path> = listOf(
        ".steam/steam",
        ".local/share/Steam",
        ".var/app/com.valvesoftware.Steam/.local/share/Steam", // Flatpak
        "snap/steam/common/.local/share/Steam",
    )
        .map { home.resolve(it) }
        .filter { it.resolve("userdata").isDirectory() }
        .map { runCatching { it.toRealPath() }.getOrDefault(it) }
        .distinct()

    /** Every Steam account on this machine that has TF3 user data. */
    fun steamUserCandidates(): List<SteamUserCandidate> = steamRoots().flatMap { root ->
        root.resolve("userdata").listDirectoryEntries()
            .filter { it.name.all(Char::isDigit) }
            .map { SteamUserCandidate(root, it.name, it.resolve(TF3_STEAM_APP_ID).resolve("local")) }
            .filter { it.localDir.isDirectory() }
    }

    /**
     * Detects all folders. [steamAccountId] picks a specific account; otherwise the most recently
     * used one wins (several Steam accounts on one machine are rare but possible).
     */
    fun detect(steamAccountId: String? = null): GamePaths {
        val candidates = steamUserCandidates()
        val user = if (steamAccountId != null) {
            candidates.firstOrNull { it.accountId == steamAccountId }
        } else {
            candidates.maxByOrNull { runCatching { it.localDir.getLastModifiedTime().toMillis() }.getOrDefault(0) }
        }
        val local = user?.localDir
        return GamePaths(
            userLocalDir = local,
            localMods = local?.resolve("mods"),
            stagingArea = local?.resolve("staging_area")?.takeIf { it.isDirectory() },
            modIoMods = modIoModsDir().takeIf { it.isDirectory() },
        )
    }

    fun modIoModsDir(): Path = home.resolve("mod.io/common").resolve(MOD_IO_GAME_ID).resolve("mods")

    companion object {
        const val TF3_STEAM_APP_ID = "3493540"
        const val MOD_IO_GAME_ID = "10640"
    }
}
