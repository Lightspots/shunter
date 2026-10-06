package ch.lightspots.shunter.core.paths

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories

/**
 * Our own folders, following the XDG base directory spec.
 * Nothing of ours is ever written into the game's mod folders except the mods themselves.
 */
data class AppDirs(val data: Path, val cache: Path, val config: Path, val state: Path) {
    /** Previous versions of mods replaced by an install. */
    val backups: Path get() = data.resolve("backups")

    /** Which mods we installed, and from where. */
    val registryFile: Path get() = data.resolve("installed.json")

    /** The mod.io access token, readable only by the user. */
    val modIoLoginFile: Path get() = config.resolve("modio-login.json")

    val downloads: Path get() = cache.resolve("downloads")
    val feeds: Path get() = cache.resolve("feeds")

    /** Scratch space for extracting archives. Kept under [data] so it is likely on the same disk as the game. */
    val work: Path get() = data.resolve("work")

    /** Log files of the frontends, one per frontend (`cli.log`). Created by the frontend's log setup, not [ensureCreated]. */
    val logs: Path get() = state.resolve("logs")

    fun ensureCreated(): AppDirs = apply {
        listOf(data, cache, config, backups, downloads, feeds, work).forEach { it.createDirectories() }
    }

    companion object {
        private const val APP_NAME = "shunter"

        fun fromEnvironment(env: Map<String, String> = System.getenv(), home: Path = Path(System.getProperty("user.home"))): AppDirs {
            fun xdg(variable: String, fallback: String): Path =
                env[variable]?.takeIf { it.isNotBlank() }?.let(::Path)?.takeIf { it.isAbsolute } ?: home.resolve(fallback)

            return AppDirs(
                data = xdg("XDG_DATA_HOME", ".local/share").resolve(APP_NAME),
                cache = xdg("XDG_CACHE_HOME", ".cache").resolve(APP_NAME),
                config = xdg("XDG_CONFIG_HOME", ".config").resolve(APP_NAME),
                state = xdg("XDG_STATE_HOME", ".local/state").resolve(APP_NAME),
            )
        }

        /** Everything below one directory, for tests and portable setups. */
        fun under(root: Path) = AppDirs(root.resolve("data"), root.resolve("cache"), root.resolve("config"), root.resolve("state"))
    }
}
