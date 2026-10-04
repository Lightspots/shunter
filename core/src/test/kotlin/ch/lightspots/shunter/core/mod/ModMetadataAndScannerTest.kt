package ch.lightspots.shunter.core.mod

import ch.lightspots.shunter.core.TestFiles
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModMetadataAndScannerTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `localized text falls back field by field`() {
        val metadata = ModMetadata.parse(
            """
            {
                "name": "ICE 4 of Deutsche Bahn",
                "summary": "The newest generation.",
                "localization": { "DE": { "name": "ICE 4 der Deutschen Bahn" } },
                "authors": [ { "name": "Modwerkstatt", "role": "CREATOR" } ],
                "tags": [ "Multiple Unit" ],
                "url": ""
            }
            """,
        )

        assertEquals("ICE 4 der Deutschen Bahn", metadata.localized("de").name)
        assertEquals("The newest generation.", metadata.localized("de").summary)
        assertEquals("ICE 4 of Deutsche Bahn", metadata.localized("fr").name)
        assertEquals(listOf(ModAuthor("Modwerkstatt", "CREATOR")), metadata.authors)
        assertEquals(listOf("Multiple Unit"), metadata.tags)
        assertNull(metadata.url)
    }

    @Test
    fun `scan reads mods and reports broken ones without failing`() {
        TestFiles.writeTree(tmp.resolve("good_mod"), TestFiles.modFiles("good_mod", name = "Good Mod"))
        // mod.io packages have mod.json but no _metadata
        TestFiles.writeTree(tmp.resolve("5822744"), mapOf("mod.json" to TestFiles.modJson("dh106_aircraftbasics_3", 5)))
        TestFiles.writeTree(tmp.resolve("broken"), mapOf("mod.json" to "{ not json"))
        tmp.resolve("empty").createDirectories()
        tmp.resolve(".hidden").createDirectories()

        val mods = ModScanner.scan(tmp, ModLocation.LOCAL).associateBy { it.folderName }

        assertEquals(setOf("good_mod", "5822744", "broken", "empty"), mods.keys)
        assertEquals("Good Mod", mods.getValue("good_mod").displayName())
        assertEquals("dh106_aircraftbasics_3", mods.getValue("5822744").displayName(), "falls back to modId")
        assertEquals(5, mods.getValue("5822744").manifest?.revision)
        assertTrue(mods.getValue("broken").problems.single().startsWith("Broken mod.json"))
        assertEquals("empty", mods.getValue("empty").displayName())
        assertEquals(listOf("No mod.json"), mods.getValue("empty").problems)
    }

    @Test
    fun `scan of a missing folder is empty`() {
        assertTrue(ModScanner.scan(tmp.resolve("missing"), ModLocation.MOD_IO).isEmpty())
    }

    @Test
    fun `finds mod roots below wrapper folders but not inside mods`() {
        TestFiles.writeTree(tmp.resolve("wrapper/mod_a"), TestFiles.modFiles("mod_a"))
        TestFiles.writeTree(tmp.resolve("mod_b"), TestFiles.modFiles("mod_b") + mapOf("content/nested/mod.json" to "{}"))

        val roots = ModScanner.findModRoots(tmp).map { tmp.relativize(it).toString() }

        assertEquals(listOf("mod_b", "wrapper/mod_a"), roots)
    }
}
