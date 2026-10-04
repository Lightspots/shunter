package ch.lightspots.shunter.core.mod

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModManifestTest {

    @Test
    fun `reads a full mod json with a wrapped dependency`() {
        val manifest = ModManifest.parse(
            """
            {
                "dependencies": [
                    {
                        "mod": { "modId": "dh106_aircraftbasics_3", "revisionMin": 1, "revisionMax": 99 },
                        "modInfo": { "displayName": "Aircraft basics", "url": "" },
                        "loadBefore": true,
                        "optional": false
                    }
                ],
                "incompatibilities": null,
                "modId": "dh106_l1011_3",
                "params": null,
                "revision": 1,
                "severityAdd": "None",
                "severityRemove": "Critical"
            }
            """,
        )

        assertEquals("dh106_l1011_3", manifest.modId)
        assertEquals(1, manifest.revision)
        assertEquals(Severity.NONE, manifest.severityAdd)
        assertEquals(Severity.CRITICAL, manifest.severityRemove)
        val dep = manifest.dependencies.single()
        assertEquals(ModReference("dh106_aircraftbasics_3", 1, 99), dep.mod)
        assertEquals("Aircraft basics", dep.displayName)
        assertNull(dep.url, "blank url is treated as missing")
        assertTrue(dep.loadBefore)
        assertFalse(dep.optional)
    }

    @Test
    fun `tolerates byte order mark, trailing commas, comments and string numbers`() {
        val manifest = ModManifest.parse(
            "\uFEFF{ // hand edited\n \"modId\": \" spaced_id \", \"revision\": \"5\", \"visible\": false, }",
        )

        assertEquals("spaced_id", manifest.modId)
        assertEquals(5, manifest.revision)
        assertFalse(manifest.visible)
        assertTrue(manifest.dependencies.isEmpty())
    }

    @Test
    fun `accepts modId as object and flat references`() {
        val manifest = ModManifest.parse(
            """
            {
                "modId": { "name": "object_id" },
                "dependencies": [ { "modId": "flat_dep", "revisionMin": -1, "revisionMax": -1, "optional": true } ],
                "incompatibilities": [ { "mod": { "modId": "bad_mod" } }, "other_bad" ]
            }
            """,
        )

        assertEquals("object_id", manifest.modId)
        assertEquals(ModReference("flat_dep"), manifest.dependencies.single().mod, "-1 means no limit")
        assertTrue(manifest.dependencies.single().optional)
        assertEquals(listOf("bad_mod", "other_bad"), manifest.incompatibilities.map { it.modId })
    }

    @Test
    fun `fails without modId`() {
        assertThrows<IllegalArgumentException> { ModManifest.parse("""{ "revision": 1 }""") }
    }

    @Test
    fun `revision range matching`() {
        val ref = ModReference("x", revisionMin = 2, revisionMax = 4)
        assertFalse(ref.matches(1))
        assertTrue(ref.matches(2))
        assertTrue(ref.matches(4))
        assertFalse(ref.matches(5))
        assertTrue(ModReference("x").matches(0))
    }
}
