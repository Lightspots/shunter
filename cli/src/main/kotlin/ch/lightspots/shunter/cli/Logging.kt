package ch.lightspots.shunter.cli

import ch.lightspots.shunter.core.logging.LogRotation
import ch.lightspots.shunter.core.paths.AppDirs
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.FileAppender
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Logback setup: everything goes to `<name>.log` in [AppDirs.logs], nothing to the console, so
 * command output stays as it is. `SHUNTER_LOG_LEVEL=debug` logs more of shunter's own steps.
 */
object Logging {
    const val LEVEL_VARIABLE = "SHUNTER_LOG_LEVEL"

    /** Rotates and opens the log file. Returns it, or null when it cannot be written (logging is off then). */
    fun setup(appDirs: AppDirs, name: String, env: Map<String, String> = System.getenv()): Path? {
        val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return null
        // Drops logback's default configuration, which logs to the console
        context.reset()
        val file = appDirs.logs.resolve("$name.log")
        try {
            file.parent.createDirectories()
            LogRotation.rotateIfLarge(file)
        } catch (e: IOException) {
            System.err.println("Warning: cannot write the log file $file, logging is off (${e.javaClass.simpleName})")
            return null
        }
        val encoder = PatternLayoutEncoder().apply {
            this.context = context
            // Several runs may write to the same file at once; the process id tells them apart
            pattern = "%d{yyyy-MM-dd HH:mm:ss.SSS} ${ProcessHandle.current().pid()} %-5level [%thread] %logger{30} - %msg%n"
            start()
        }
        val appender = FileAppender<ILoggingEvent>().apply {
            this.context = context
            this.name = "file"
            this.file = file.toString()
            this.encoder = encoder
            start()
        }
        if (!appender.isStarted) {
            System.err.println("Warning: cannot open the log file $file, logging is off")
            return null
        }
        context.getLogger(Logger.ROOT_LOGGER_NAME).apply {
            level = Level.INFO
            addAppender(appender)
        }
        // Only our own loggers: debug output of libraries (Ktor) is noisy and may contain request details
        context.getLogger("ch.lightspots.shunter").level = Level.toLevel(env[LEVEL_VARIABLE], Level.INFO)
        return file
    }
}
