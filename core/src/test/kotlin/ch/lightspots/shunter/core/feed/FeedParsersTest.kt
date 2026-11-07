package ch.lightspots.shunter.core.feed

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedParsersTest {

    /** Trimmed copy of the real tpf3-v3.json structure (2026-10). */
    private val tfnet = """
        {
            "repo": { "format": "TransportFeverNetRepo", "version": 3, "name": "transportfever.net TPF3" },
            "files": [
                {
                    "entry_id": 8097,
                    "name": "Lockheed L-1011",
                    "author": { "id": 31368, "name": "DH-106" },
                    "entry_url": "https://www.transportfever.net/filebase/entry/8097/",
                    "updated": 1791129919,
                    "tags": [ "Flugzeug" ],
                    "custom_fields": {
                        "option8": { "id": 8, "label": "Aktuelle Version", "value": "1.2" },
                        "option9": { "id": 9, "label": "Status", "value": "Stable" }
                    },
                    "dependencies": [
                        {
                            "type": "filebase",
                            "entry_id": 8095,
                            "required": true,
                            "name": "Werkzeugkasten für Flugzeugmodelle",
                            "entry_url": "https://www.transportfever.net/filebase/entry/8095/",
                            "install": {
                                "file_id": 16812,
                                "filename": "Aircraft Toolbox TPF3 1.4.7z",
                                "size": 2860321,
                                "sha256": "494298218E52C985C0A9C4C07EDAE9D485F57F8FC404F43DBD0DE82996380231",
                                "download": "https://www.transportfever.net/filebase/entry-download/8095/?fileID=16812"
                            }
                        },
                        { "type": "external", "raw": "https://example.org/mod", "required": false, "name": "External" }
                    ],
                    "latest_file": {
                        "file_id": 16920,
                        "filename": "l1011.7z",
                        "size": 169644322,
                        "changed": 1791129919,
                        "download": "https://www.transportfever.net/filebase/entry-download/8097/?fileID=16920",
                        "sha256": "b1a006843a50975a4c46a409d419c32c66bf5cff5ce02eaca04caf676a058a48"
                    }
                },
                { "entry_id": 1, "name": "No file yet", "latest_file": null, "dependencies": [] },
                { "name": "No id, skipped" }
            ]
        }
    """.trimIndent()

    @Test
    fun `parses transportfever net entries`() {
        val mods = TfnetFeedParser.parse(tfnet)

        assertEquals(listOf("8097", "1"), mods.map { it.id })
        val mod = mods.first()
        assertEquals("tfnet:8097", mod.ref)
        assertEquals("DH-106", mod.author)
        assertEquals("1.2", mod.version)
        assertEquals(listOf("Flugzeug"), mod.tags)
        val file = mod.files.single()
        assertEquals("16920", file.id)
        assertEquals(169644322, file.size)
        assertEquals(1791129919, file.changedAt)

        val (toolbox, external) = mod.dependencies
        assertEquals("8095", toolbox.remoteId)
        assertTrue(toolbox.required)
        assertEquals("494298218e52c985c0a9c4c07edae9d485f57f8fc404f43dbd0de82996380231", toolbox.file?.sha256, "checksums are lowercased")
        assertEquals(null, external.remoteId, "only filebase dependencies point to entries")
        assertEquals("https://example.org/mod", external.pageUrl)

        assertTrue(mods[1].files.isEmpty())
    }

    @Test
    fun `rejects other formats`() {
        assertThrows<FeedFormatException> { TfnetFeedParser.parse("""{ "repo": { "format": "CommonAPIRepo" }, "files": [] }""") }
        assertThrows<FeedFormatException> { ModwerkstattFeedParser.parse("""{ "repo_info": {}, "mods": [] }""") }
    }

    @Test
    fun `parses modwerkstatt entries`() {
        val text = """
            [
                { "id": 9001, "modId": "mw_ice_4", "slug": "ice-4", "title": "ICE 4",
                  "image_url": "https://modwerkstatt.com/img/models/tpf3/ice-4/preview.webp",
                  "download_url": "https://modwerkstatt.com/download/tpf3/mw_ice_4.zip", "file_size": 1234,
                  "revision": "2", "updates": { "2": "Fixed the driver" },
                  "required_mods": [ { "id": 9002, "modId": "mw_basis" }, { "id": 9999, "modId": "mw_unlisted" } ],
                  "categories": [ { "id": 1, "name": "Trains" } ] },
                { "id": 9002, "modId": "mw_basis", "title": "Basis Mod", "download_url": "https://modwerkstatt.com/download/tpf3/mw_basis.zip",
                  "file_size": 0, "revision": 1, "updates": [], "required_mods": [] },
                { "id": 9003, "modId": "mw_soon", "title": "Not downloadable yet", "download_url": "", "revision": "1" },
                { "modId": "no_id" }
            ]
        """.trimIndent()

        val mods = ModwerkstattFeedParser.parse(text)

        assertEquals(listOf("modwerkstatt:9001", "modwerkstatt:9002", "modwerkstatt:9003"), mods.map { it.ref })
        val ice = mods[0]
        assertEquals("ICE 4", ice.name)
        assertEquals("rev 2", ice.version)
        assertEquals(listOf("Trains"), ice.tags)
        val file = ice.files.single()
        assertEquals("9001-r2", file.id)
        assertEquals("mw_ice_4.zip", file.fileName)
        assertEquals("https://modwerkstatt.com/download/tpf3/mw_ice_4.zip", file.downloadUrl)
        assertEquals(1234, file.size)
        assertEquals(
            listOf(RemoteDependency("Basis Mod", "9002", true), RemoteDependency("mw_unlisted", null, true)),
            ice.dependencies,
        )
        assertEquals("rev 1", mods[1].version, "a number works too")
        assertNull(mods[1].files.single().size, "0 means unknown")
        assertTrue(mods[2].files.isEmpty())
    }
}
