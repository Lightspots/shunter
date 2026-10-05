package ch.lightspots.shunter.cli

import ch.lightspots.shunter.core.BuildInfo
import ch.lightspots.shunter.core.ModManager
import ch.lightspots.shunter.core.net.HttpDownloader
import ch.lightspots.shunter.core.paths.AppDirs
import ch.lightspots.shunter.core.paths.GamePathDetector
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.registerCloseable
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.path
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.util.Locale

private val logger = KotlinLogging.logger {}

/** Shared state for all subcommands, created by [Shunter]. [logFile] is null when logging is off. */
class CliContext(
    val detector: GamePathDetector,
    val manager: ModManager,
    val http: HttpDownloader,
    val language: String,
    val logFile: Path? = null,
)

class Shunter(private val logFile: Path? = null) : CliktCommand(name = "shunter") {
    override fun help(context: Context) = "Mod manager for Transport Fever 3 on Linux."

    private val modsDir by option(
        "--mods-dir",
        help = "Install into this folder instead of the game's local/mods folder (useful for trying things out)",
    ).path(canBeFile = false)

    private val steamAccount by option(
        "--steam-account",
        help = "Steam account id (folder name below Steam/userdata) if there is more than one",
    )

    private val language by option("--lang", help = "Language for mod names, e.g. de or en (default: system language)")

    init {
        versionOption(BuildInfo.VERSION)
    }

    override fun run() {
        // Subcommands get it via requireObject; one set beforehand (e.g. by a test) is kept
        currentContext.findOrSetObject {
            val detector = GamePathDetector()
            val detected = detector.detect(steamAccount)
            val paths = modsDir?.let { detected.copy(localMods = it.toAbsolutePath()) } ?: detected
            val http = currentContext.registerCloseable(HttpDownloader())
            CliContext(
                detector = detector,
                manager = ModManager(paths, AppDirs.fromEnvironment(), http),
                http = http,
                language = language ?: Locale.getDefault().language,
                logFile = logFile,
            )
        }
    }
}

fun main(args: Array<String>) {
    val logFile = Logging.setup(AppDirs.fromEnvironment(), "cli")
    // Not the raw arguments: they may hold secrets (an API key), even ones Clikt rejects later
    logger.info {
        "shunter ${BuildInfo.VERSION} (cli), Java ${Runtime.version()}, ${System.getProperty(
            "os.name",
        )} ${System.getProperty("os.version")}"
    }
    try {
        Shunter(logFile)
            .subcommands(
                PathsCommand(),
                ListCommand(),
                SearchCommand(),
                InstallCommand(),
                UpdatesCommand(),
                UpdateCommand(),
                RemoveCommand(),
            )
            .main(args)
    } catch (e: Exception) {
        // Clikt handles its own errors; anything else is a bug
        logger.error(e) { "Unexpected error" }
        throw e
    }
}
