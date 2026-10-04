package ch.lightspots.shunter.core.archive

import ch.lightspots.shunter.core.TestFiles
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ArchiveExtractorTest {

    @TempDir
    lateinit var tmp: Path

    private val files = mapOf(
        "my_mod/mod.json" to """{ "modId": "my_mod" }""",
        "my_mod/content/res/a.lua" to "return 1",
    )

    @Test
    fun `extracts zip`() {
        val archive = TestFiles.zip(tmp.resolve("mod.zip"), files)
        val out = tmp.resolve("out")

        ArchiveExtractor.extract(archive, out)

        assertEquals("return 1", out.resolve("my_mod/content/res/a.lua").readText())
    }

    @Test
    fun `extracts 7z regardless of file extension`() {
        val archive = TestFiles.sevenZip(tmp.resolve("download.bin"), files)
        val out = tmp.resolve("out")

        assertEquals(ArchiveFormat.SEVEN_ZIP, ArchiveExtractor.detectFormat(archive))
        ArchiveExtractor.extract(archive, out)

        assertEquals("""{ "modId": "my_mod" }""", out.resolve("my_mod/mod.json").readText())
    }

    @Test
    fun `normalizes backslashes and skips macOS metadata`() {
        val archive = TestFiles.zip(
            tmp.resolve("mod.zip"),
            mapOf("my_mod\\mod.json" to "{}", "__MACOSX/my_mod/._mod.json" to "junk"),
        )
        val out = tmp.resolve("out")

        ArchiveExtractor.extract(archive, out)

        assertEquals("{}", out.resolve("my_mod/mod.json").readText())
        assertFalse(out.resolve("__MACOSX").exists())
    }

    @ParameterizedTest
    @ValueSource(strings = ["../evil.txt", "my_mod/../../evil.txt", "/tmp/evil.txt", "C:/evil.txt", "..\\evil.txt"])
    fun `refuses entries that escape the target`(name: String) {
        val archive = TestFiles.zip(tmp.resolve("evil.zip"), mapOf(name to "pwned"))
        val out = tmp.resolve("sub/out")

        assertThrows<ArchiveException> { ArchiveExtractor.extract(archive, out) }

        assertFalse(tmp.resolve("evil.txt").exists())
        assertFalse(tmp.resolve("sub/evil.txt").exists())
    }

    @Test
    fun `refuses non-empty target and unknown formats`() {
        val archive = TestFiles.zip(tmp.resolve("mod.zip"), files)
        val out = tmp.resolve("out")
        TestFiles.writeTree(out, mapOf("existing" to "x"))
        assertThrows<ArchiveException> { ArchiveExtractor.extract(archive, out) }

        val text = tmp.resolve("not-an-archive.zip").also { it.writeText("hello") }
        assertThrows<ArchiveException> { ArchiveExtractor.detectFormat(text) }

        val rar = tmp.resolve("mod.rar").also { it.writeText("Rar!\u001A\u0007\u0000rest") }
        val e = assertThrows<ArchiveException> { ArchiveExtractor.detectFormat(rar) }
        assertEquals(true, e.message?.contains("RAR"))
    }

    @Test
    fun `resolveEntry ignores dot segments and the root`() {
        val root = tmp.toRealPath()
        assertNull(ArchiveExtractor.resolveEntry(root, "./"))
        assertEquals(root.resolve("a/b"), ArchiveExtractor.resolveEntry(root, "./a//b"))
    }
}
