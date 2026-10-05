package ch.lightspots.shunter.core.paths

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.setLastModifiedTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GamePathDetectorTest {

    @TempDir
    lateinit var home: Path

    private fun userLocal(account: String): Path = home.resolve(".local/share/Steam/userdata/$account/3493540/local").createDirectories()

    @Test
    fun `detects native Steam folders and mod io`() {
        val local = userLocal("53587594")
        local.resolve("staging_area").createDirectories()
        home.resolve("mod.io/common/10640/mods").createDirectories()
        // ~/.steam/steam is normally a symlink to the real Steam folder; it must not count twice
        home.resolve(".steam").createDirectories()
        home.resolve(".steam/steam").createSymbolicLinkPointingTo(home.resolve(".local/share/Steam"))

        val detector = GamePathDetector(home)
        val paths = detector.detect()

        assertEquals(1, detector.steamRoots().size)
        assertEquals(local.toRealPath(), paths.userLocalDir)
        assertEquals(local.toRealPath().resolve("mods"), paths.localMods, "mods folder is returned even before it exists")
        assertEquals(local.toRealPath().resolve("staging_area"), paths.stagingArea)
        assertEquals(home.resolve("mod.io/common/10640/mods"), paths.modIoMods)
    }

    @Test
    fun `picks the most recently used account unless one is given`() {
        userLocal("111").setLastModifiedTime(FileTime.from(Instant.parse("2026-01-01T00:00:00Z")))
        userLocal("222").setLastModifiedTime(FileTime.from(Instant.parse("2026-09-01T00:00:00Z")))
        // An account without TF3 data is ignored
        home.resolve(".local/share/Steam/userdata/333/730").createDirectories()

        val detector = GamePathDetector(home)

        assertEquals(setOf("111", "222"), detector.steamUserCandidates().map { it.accountId }.toSet())
        assertEquals("222", detector.detect().userLocalDir?.parent?.parent?.fileName.toString())
        assertEquals("111", detector.detect("111").userLocalDir?.parent?.parent?.fileName.toString())
    }

    @Test
    fun `nothing found`() {
        val paths = GamePathDetector(home).detect()

        assertNull(paths.localMods)
        assertNull(paths.stagingArea)
        assertNull(paths.modIoMods)
    }

    @Test
    fun `app dirs follow XDG variables`() {
        val dirs = AppDirs.fromEnvironment(mapOf("XDG_DATA_HOME" to "/data", "XDG_CACHE_HOME" to "relative/ignored"), home)

        assertEquals(Path.of("/data/shunter"), dirs.data)
        assertEquals(home.resolve(".cache/shunter"), dirs.cache, "relative XDG paths are invalid per spec")
        assertEquals(home.resolve(".config/shunter"), dirs.config)
    }
}
