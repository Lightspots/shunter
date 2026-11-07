package ch.lightspots.shunter.app

import ch.lightspots.shunter.core.mod.ModReference
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FormatTest {
    @Test
    fun `sizes use decimal units`() {
        assertEquals(null, humanSize(null))
        assertEquals("999 B", humanSize(999))
        assertEquals("14.2 MB", humanSize(14_200_000))
    }

    @Test
    fun `revision ranges read as text`() {
        assertEquals("", ModReference("a").revisionText())
        assertEquals("revision 2 or later", ModReference("a", revisionMin = 2).revisionText())
        assertEquals("revision 3 or earlier", ModReference("a", revisionMax = 3).revisionText())
        assertEquals("revision 2 to 3", ModReference("a", 2, 3).revisionText())
        assertEquals("a ≥ 2", ModReference("a", revisionMin = 2).shortText())
    }

    @Test
    fun `initials take the first letters of two words`() {
        assertEquals("R4", initials("Re 4/4 II Electric Locomotive"))
        assertEquals("BS", initials("Block-Signal Set"))
        assertEquals("MD", initials("my_depot_test"))
        assertEquals("?", initials("—"))
    }

    @Test
    fun `long paths keep their end`() {
        assertEquals("…/3493540/local/mods", shortPath("/home/me/.steam/steam/userdata/1/3493540/local/mods"))
        assertEquals("/a/b", shortPath("/a/b"))
    }

    @Test
    fun `ages are rounded`() {
        val now = Instant.parse("2026-11-07T12:00:00Z")
        assertEquals("just now", ago(now.minusSeconds(20), now))
        assertEquals("12 min ago", ago(now.minusSeconds(12 * 60), now))
        assertEquals("3 h ago", ago(now.minusSeconds(3 * 3600), now))
        assertEquals("4 days ago", ago(now.minusSeconds(4 * 86400), now))
    }

    @Test
    fun `options are parsed like the cli's`() {
        assertEquals(AppOptions(modsDir = "/tmp/m", language = "de"), AppOptions.parse(arrayOf("--mods-dir", "/tmp/m", "--lang=de")))
        assertFailsWith<IllegalArgumentException> { AppOptions.parse(arrayOf("--mods-dir")) }
        assertFailsWith<IllegalArgumentException> { AppOptions.parse(arrayOf("--nope", "x")) }
    }
}
