package ch.lightspots.shunter.core.feed

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
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
        assertThrows<FeedFormatException> { ModwerkstattFeedParser.parse("""{ "files": [] }""") }
    }

    @Test
    fun `parses modwerkstatt entries for the requested game only`() {
        val text = """
            {
                "repo_info": { "name": "ModWerkstatt", "changed": 1787073362 },
                "mod_base_url": "https://modwerkstatt.com/downloads/",
                "file_base_url": "https://modwerkstatt.com/download/",
                "mods": [
                    { "id": 5204, "name": "MAN SL 202", "game": "tpf2", "version": "1.0", "url": "man-sl-202",
                      "files": [ { "fileid": "5204", "filename": "a.zip", "url": "a.zip", "foldername": "a_1" } ] },
                    { "id": 9001, "name": "ICE 4", "author": "ModWerkstatt", "game": "tpf3", "version": 2,
                      "url": "tpf3/ice-4", "timechanged": 1790000000, "tags": [ "TPF3 Züge" ],
                      "files": [
                        { "fileid": "9001", "filename": "mw_ice_4.zip", "url": "mw_ice_4.zip", "timechanged": 1790000000, "foldername": "mw_ice_4" },
                        { "fileid": "9002", "filename": "mw_ice_4_fake.zip", "url": "https://cdn.example/fake.zip", "foldername": "mw_ice_4_fake" }
                      ] }
                ]
            }
        """.trimIndent()

        val mod = ModwerkstattFeedParser.parse(text).single()

        assertEquals("modwerkstatt:9001", mod.ref)
        assertEquals("2", mod.version)
        assertEquals("https://modwerkstatt.com/downloads/tpf3/ice-4", mod.pageUrl)
        assertEquals(
            listOf("https://modwerkstatt.com/download/mw_ice_4.zip", "https://cdn.example/fake.zip"),
            mod.files.map { it.downloadUrl },
        )
        assertEquals(listOf("mw_ice_4", "mw_ice_4_fake"), mod.files.map { it.folderName })
        assertEquals("a_1", ModwerkstattFeedParser.parse(text, game = "tpf2").single().files.single().folderName)
    }
}
