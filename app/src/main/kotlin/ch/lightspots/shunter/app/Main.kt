package ch.lightspots.shunter.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ch.lightspots.shunter.app.ui.ShunterApp
import ch.lightspots.shunter.app.ui.ShunterTheme
import ch.lightspots.shunter.core.BuildInfo
import ch.lightspots.shunter.core.ModManager
import ch.lightspots.shunter.core.net.HttpDownloader
import ch.lightspots.shunter.core.paths.AppDirs
import ch.lightspots.shunter.core.paths.GamePathDetector
import ch.lightspots.shunter.logging.Logging
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.Dimension
import java.util.Locale
import kotlin.io.path.Path
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

/** Command-line options, the same as the CLI's global ones. */
internal data class AppOptions(val modsDir: String? = null, val steamAccount: String? = null, val language: String? = null) {
    companion object {
        fun parse(args: Array<String>): AppOptions {
            var options = AppOptions()
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                val name = arg.substringBefore('=')
                val value = if ('=' in arg) arg.substringAfter('=') else args.getOrNull(++i)
                require(value != null) { "$name needs a value" }
                options = when (name) {
                    "--mods-dir" -> options.copy(modsDir = value)
                    "--steam-account" -> options.copy(steamAccount = value)
                    "--lang" -> options.copy(language = value)
                    else -> throw IllegalArgumentException("Unknown option $name")
                }
                i++
            }
            return options
        }

        const val USAGE = "Usage: shunter-app [--mods-dir <folder>] [--steam-account <id>] [--lang <language>]"
    }
}

fun main(args: Array<String>) {
    val options = try {
        AppOptions.parse(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("${e.message}\n${AppOptions.USAGE}")
        exitProcess(2)
    }
    val appDirs = AppDirs.fromEnvironment()
    val logFile = Logging.setup(appDirs, "app")
    logger.info {
        "shunter ${BuildInfo.VERSION} (app), Java ${Runtime.version()}, " +
            "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
    }
    Thread.setDefaultUncaughtExceptionHandler { thread, e -> logger.error(e) { "Uncaught exception in ${thread.name}" } }

    val detected = GamePathDetector().detect(options.steamAccount)
    val paths = options.modsDir?.let { detected.copy(localMods = Path(it).toAbsolutePath()) } ?: detected
    val http = HttpDownloader()
    val manager = ModManager(paths, appDirs, http)
    val settings = AppSettings(appDirs.config.resolve("app.properties"))

    application {
        val windowState = rememberWindowState(size = DpSize(1280.dp, 820.dp))
        Window(
            onCloseRequest = {
                http.close()
                exitApplication()
            },
            state = windowState,
            title = "Shunter",
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(1000, 640) }
            val scope = rememberCoroutineScope()
            val model = remember {
                AppModel(manager, options.language ?: Locale.getDefault().language, logFile, settings, scope).also { it.start() }
            }
            val dark = when (model.theme) {
                ThemeChoice.SYSTEM -> isSystemInDarkTheme()
                ThemeChoice.LIGHT -> false
                ThemeChoice.DARK -> true
            }
            ShunterTheme(dark) {
                ShunterApp(model)
            }
        }
    }
}
