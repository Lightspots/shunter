package ch.lightspots.shunter.logging

import ch.lightspots.shunter.core.paths.AppDirs
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LoggingTest {
    @Test
    fun `logs to a file named after the frontend`(@TempDir tmp: Path) {
        val dirs = AppDirs.under(tmp)

        val file = Logging.setup(dirs, "test", env = emptyMap())

        assertEquals(dirs.logs.resolve("test.log"), file)
        val logger = KotlinLogging.logger("ch.lightspots.shunter.LoggingTest")
        logger.info { "hello file" }
        logger.debug { "not at info" }
        val text = file!!.readText()
        assertContains(text, "hello file")
        assertFalse("not at info" in text)
    }

    @Test
    fun `the level variable raises only our own loggers`(@TempDir tmp: Path) {
        val file = Logging.setup(AppDirs.under(tmp), "debug", env = mapOf(Logging.LEVEL_VARIABLE to "debug"))!!

        KotlinLogging.logger("ch.lightspots.shunter.LoggingTest").debug { "ours in debug" }
        KotlinLogging.logger("io.ktor.Something").debug { "library in debug" }
        val text = file.readText()
        assertContains(text, "ours in debug")
        assertFalse("library in debug" in text)
    }
}
