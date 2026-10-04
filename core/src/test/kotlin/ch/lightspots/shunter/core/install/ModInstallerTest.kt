package ch.lightspots.shunter.core.install

import ch.lightspots.shunter.core.TestFiles
import ch.lightspots.shunter.core.feed.FeedSource
import ch.lightspots.shunter.core.paths.AppDirs
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModInstallerTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var appDirs: AppDirs
    private lateinit var modsDir: Path
    private lateinit var registry: InstallRegistry
    private val clock = MutableClock(Instant.parse("2026-10-04T12:00:00Z"))

    @BeforeEach
    fun setUp() {
        appDirs = AppDirs.under(tmp.resolve("app"))
        modsDir = tmp.resolve("game/mods")
        registry = InstallRegistry(appDirs.registryFile)
    }

    private fun installer(keepBackups: Int = 2) = ModInstaller(appDirs, registry, clock, keepBackups)

    private fun archive(name: String, files: Map<String, String>) = TestFiles.zip(tmp.resolve("archives/$name"), files)

    @Test
    fun `installs a mod and records where it came from`() {
        val zip = archive("a.zip", TestFiles.inFolder("my_mod", TestFiles.modFiles("my_mod", revision = 3)))
        val origin = InstallOrigin(source = FeedSource.TFNET, remoteId = "8126", fileId = "16920")

        val result = installer().install(zip, modsDir, origin, archiveSha256 = "abc")

        val installed = result.single()
        assertEquals("my_mod", installed.folderName)
        assertEquals(3, installed.revision)
        assertNull(installed.backup)
        assertEquals("revision 3", modsDir.resolve("my_mod/content/readme.txt").readText())
        val record = assertNotNull(registry.get(modsDir, "my_mod"))
        assertEquals(origin, record.origin)
        assertTrue(appDirs.registryFile.readText().contains("\"source\": \"tfnet\""), "feed stored by its id")
        assertEquals("abc", record.archiveSha256)
        assertEquals(clock.instant().epochSecond, record.installedAt)
        assertTrue(appDirs.work.listDirectoryEntries().isEmpty(), "work folder is cleaned up")
    }

    @Test
    fun `update backs up the old version`() {
        installer().install(archive("v1.zip", TestFiles.inFolder("my_mod", TestFiles.modFiles("my_mod", 1))), modsDir)
        modsDir.resolve("my_mod/content/old_only.txt").writeText("gone after update")

        clock.advanceSeconds(60)
        val result = installer().install(archive("v2.zip", TestFiles.inFolder("my_mod", TestFiles.modFiles("my_mod", 2))), modsDir)

        val backup = assertNotNull(result.single().backup)
        assertEquals("revision 1", backup.resolve("content/readme.txt").readText())
        assertEquals("revision 2", modsDir.resolve("my_mod/content/readme.txt").readText())
        assertFalse(modsDir.resolve("my_mod/content/old_only.txt").exists(), "update replaces, it does not merge")
        assertEquals(2, registry.get(modsDir, "my_mod")?.revision)
    }

    @Test
    fun `old backups are pruned`() {
        repeat(4) { i ->
            clock.advanceSeconds(1)
            installer(keepBackups = 2).install(archive("v$i.zip", TestFiles.inFolder("m", TestFiles.modFiles("m", i))), modsDir)
        }

        assertEquals(2, appDirs.backups.resolve("m").listDirectoryEntries().size)
    }

    @Test
    fun `installs several mods from one archive and mod json at the archive top`() {
        val multi = archive(
            "multi.zip",
            TestFiles.inFolder("pack/mod_a", TestFiles.modFiles("mod_a")) + TestFiles.inFolder("pack/mod_b", TestFiles.modFiles("mod_b")),
        )
        assertEquals(listOf("mod_a", "mod_b"), installer().install(multi, modsDir).map { it.folderName })

        val flat = archive("flat.zip", TestFiles.modFiles("flat_mod"))
        assertEquals("flat_mod", installer().install(flat, modsDir).single().folderName)
        assertTrue(modsDir.resolve("flat_mod/mod.json").exists())
    }

    @Test
    fun `archive without a mod changes nothing`() {
        val zip = archive("nomod.zip", mapOf("readme.txt" to "hello"))

        assertThrows<InstallException> { installer().install(zip, modsDir) }

        assertFalse(modsDir.exists())
        assertTrue(appDirs.work.listDirectoryEntries().isEmpty())
    }

    @Test
    fun `refuses unsafe folder names from mod json`() {
        val zip = archive("evil.zip", mapOf("mod.json" to """{ "modId": "../../outside" }"""))

        assertThrows<InstallException> { installer().install(zip, modsDir) }

        assertFalse(tmp.resolve("outside").exists())
    }

    @Test
    fun `uninstall moves the mod to the backups`() {
        installer().install(archive("a.zip", TestFiles.inFolder("my_mod", TestFiles.modFiles("my_mod"))), modsDir)

        val backup = installer().uninstall(modsDir, "my_mod")

        assertFalse(modsDir.resolve("my_mod").exists())
        assertTrue(backup.resolve("mod.json").exists())
        assertNull(registry.get(modsDir, "my_mod"))
        assertThrows<InstallException> { installer().uninstall(modsDir, "../game") }
    }

    class MutableClock(private var now: Instant) : Clock() {
        fun advanceSeconds(seconds: Long) {
            now = now.plusSeconds(seconds)
        }

        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
