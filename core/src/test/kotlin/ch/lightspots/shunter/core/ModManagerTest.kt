package ch.lightspots.shunter.core

import ch.lightspots.shunter.core.feed.FeedResult
import ch.lightspots.shunter.core.feed.FeedSource
import ch.lightspots.shunter.core.feed.RemoteFile
import ch.lightspots.shunter.core.feed.RemoteMod
import ch.lightspots.shunter.core.install.InstallOrigin
import ch.lightspots.shunter.core.install.InstallRecord
import ch.lightspots.shunter.core.paths.AppDirs
import ch.lightspots.shunter.core.paths.GamePaths
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModManagerTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var manager: ModManager

    @BeforeEach
    fun setUp() {
        manager = ModManager(
            GamePaths(
                userLocalDir = tmp.resolve("local"),
                localMods = tmp.resolve("local/mods"),
                stagingArea = tmp.resolve("local/staging_area"),
                modIoMods = tmp.resolve("modio"),
            ),
            AppDirs.under(tmp.resolve("app")),
        )
    }

    private fun record(folder: String, source: String, remoteId: String, fileId: String, changedAt: Long? = null) =
        manager.registry.put(
            manager.modsDir,
            InstallRecord(folder, folder, 1, InstallOrigin(source, remoteId, fileId, remoteChangedAt = changedAt), installedAt = 0),
        )

    private fun feed(source: FeedSource, vararg mods: RemoteMod) = FeedResult(source, mods.toList(), Instant.EPOCH)

    @Test
    fun `detects updates by file id and change time`() {
        record("new_file", "tfnet", "1", fileId = "10")
        record("same_file", "tfnet", "2", fileId = "20")
        record("changed_file", "modwerkstatt", "3", fileId = "30", changedAt = 100)
        // Installed from a local archive: no origin, never an update candidate
        manager.registry.put(manager.modsDir, InstallRecord("local_archive", "local_archive", installedAt = 0))

        val updates = manager.availableUpdates(
            listOf(
                feed(
                    FeedSource.TFNET,
                    RemoteMod(FeedSource.TFNET, "1", "One", files = listOf(RemoteFile("11", "one.zip", "u"))),
                    RemoteMod(FeedSource.TFNET, "2", "Two", files = listOf(RemoteFile("20", "two.zip", "u"))),
                ),
                feed(
                    FeedSource.MODWERKSTATT,
                    RemoteMod(
                        FeedSource.MODWERKSTATT, "3", "Three",
                        files = listOf(
                            RemoteFile("31", "other.zip", "u", folderName = "other_folder"),
                            RemoteFile("30", "three.zip", "u", changedAt = 200, folderName = "changed_file"),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(setOf("new_file", "changed_file"), updates.map { it.record.folderName }.toSet())
        assertEquals("30", updates.single { it.record.folderName == "changed_file" }.file.id)
    }

    @Test
    fun `reports missing and wrong revision dependencies across all locations`() {
        val deps = """[
            { "mod": { "modId": "base_lib", "revisionMin": 3 } },
            { "mod": { "modId": "absent_lib" }, "modInfo": { "displayName": "Absent" } },
            { "mod": { "modId": "optional_lib" }, "optional": true },
            { "mod": { "modId": "modio_lib" } }
        ]"""
        TestFiles.writeTree(tmp.resolve("local/mods/user_mod"), mapOf("mod.json" to TestFiles.modJson("user_mod", dependencies = deps)))
        TestFiles.writeTree(tmp.resolve("local/mods/base_lib"), mapOf("mod.json" to TestFiles.modJson("base_lib", revision = 2)))
        TestFiles.writeTree(tmp.resolve("modio/5822744"), mapOf("mod.json" to TestFiles.modJson("modio_lib")))

        val missing = manager.missingDependencies(manager.scan())

        assertEquals(listOf("base_lib", "absent_lib"), missing.map { it.dependency.modId })
        assertEquals(2, missing[0].wrongRevision)
        assertEquals("Absent", missing[1].displayName)
    }

    @Test
    fun `finds mod ids installed twice`() {
        TestFiles.writeTree(tmp.resolve("local/mods/dup"), mapOf("mod.json" to TestFiles.modJson("dup")))
        TestFiles.writeTree(tmp.resolve("modio/123"), mapOf("mod.json" to TestFiles.modJson("dup")))
        TestFiles.writeTree(tmp.resolve("local/mods/single"), mapOf("mod.json" to TestFiles.modJson("single")))

        val duplicates = manager.duplicateModIds()

        assertEquals(setOf("dup"), duplicates.keys)
        assertTrue(duplicates.getValue("dup").map { it.folderName }.containsAll(listOf("dup", "123")))
    }
}
