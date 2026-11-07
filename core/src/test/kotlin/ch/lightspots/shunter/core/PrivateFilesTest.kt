package ch.lightspots.shunter.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrivateFilesTest {

    @TempDir
    lateinit var tmp: Path

    private val ownerOnly = PosixFilePermissions.fromString("rw-------")

    @Test
    fun `writes a file only the user can read and leaves no temporary file`() {
        val file = tmp.resolve("dir/login.json")
        // A leftover of an older version that used a fixed temporary name
        tmp.resolve("dir").toFile().mkdirs()
        tmp.resolve("dir/login.json.tmp").writeText("old")

        writePrivateFile(file, "secret")
        writePrivateFile(file, "newer")

        assertEquals("newer", file.readText())
        assertEquals(ownerOnly, file.getPosixFilePermissions())
        assertEquals(setOf("login.json", "login.json.tmp"), tmp.resolve("dir").listDirectoryEntries().map { it.name }.toSet())
    }

    @Test
    fun `concurrent writers each use their own temporary file`() {
        val file = tmp.resolve("feed.json")
        val pool = Executors.newFixedThreadPool(8)
        val failures = (1..8).map { writer ->
            pool.submit<Throwable?> {
                runCatching { repeat(50) { writePrivateFile(file, "writer $writer, round $it") } }.exceptionOrNull()
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))

        assertEquals(emptyList(), failures.mapNotNull { it.get() })
        assertEquals(ownerOnly, file.getPosixFilePermissions())
        assertEquals(listOf(file), tmp.listDirectoryEntries())
    }
}
