package ch.lightspots.shunter.core.logging

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LogRotationTest {

    @TempDir
    lateinit var dir: Path

    private val log: Path get() = dir.resolve("cli.log")

    @Test
    fun `small file is kept`() {
        log.writeText("12345")

        LogRotation.rotateIfLarge(log, maxBytes = 10)

        assertEquals("12345", log.readText())
        assertEquals(listOf("cli.log"), dir.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `missing file is fine`() {
        LogRotation.rotateIfLarge(log, maxBytes = 10)

        assertFalse(log.exists())
    }

    @Test
    fun `large file is rotated and the oldest dropped`() {
        log.writeText("current run")
        dir.resolve("cli.1.log").writeText("one")
        dir.resolve("cli.2.log").writeText("two")

        LogRotation.rotateIfLarge(log, maxBytes = 10, keep = 2)

        assertFalse(log.exists())
        assertEquals("current run", dir.resolve("cli.1.log").readText())
        assertEquals("one", dir.resolve("cli.2.log").readText())
        assertEquals(listOf("cli.1.log", "cli.2.log"), dir.listDirectoryEntries().map { it.name }.sorted())
    }

    @Test
    fun `gaps in old files are fine`() {
        log.writeText("current run")
        dir.resolve("cli.2.log").writeText("two")

        LogRotation.rotateIfLarge(log, maxBytes = 10, keep = 3)

        assertEquals("current run", dir.resolve("cli.1.log").readText())
        assertEquals("two", dir.resolve("cli.3.log").readText())
        assertFalse(dir.resolve("cli.2.log").exists())
    }
}
