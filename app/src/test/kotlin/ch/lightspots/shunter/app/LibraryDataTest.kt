package ch.lightspots.shunter.app

import ch.lightspots.shunter.core.AvailableUpdate
import ch.lightspots.shunter.core.ChangedOutside
import ch.lightspots.shunter.core.MissingDependency
import ch.lightspots.shunter.core.feed.FeedSource
import ch.lightspots.shunter.core.feed.RemoteFile
import ch.lightspots.shunter.core.feed.RemoteMod
import ch.lightspots.shunter.core.install.InstallOrigin
import ch.lightspots.shunter.core.install.InstallRecord
import ch.lightspots.shunter.core.mod.InstalledMod
import ch.lightspots.shunter.core.mod.ModLocation
import ch.lightspots.shunter.core.mod.ModManifest
import ch.lightspots.shunter.core.mod.ModReference
import ch.lightspots.shunter.core.modio.ModIoMod
import ch.lightspots.shunter.core.modio.ModIoSubscriptions
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryDataTest {
    private fun mod(
        folder: String,
        location: ModLocation = ModLocation.LOCAL,
        modId: String? = folder,
        revision: Int = 1,
        problems: List<String> = emptyList(),
    ) = InstalledMod(
        location = location,
        folder = Path("/mods", location.name.lowercase(), folder),
        manifest = modId?.let { ModManifest(it, revision) },
        metadata = null,
        problems = problems,
    )

    private fun record(folder: String, source: FeedSource? = FeedSource.TFNET, version: String? = "1.2") = InstallRecord(
        folderName = folder,
        modId = folder,
        revision = 1,
        origin = InstallOrigin(source = source, remoteId = source?.let { "8126" }, fileId = "1", version = version),
        installedAt = 0,
    )

    private fun update(folder: String) = AvailableUpdate(
        record(folder),
        RemoteMod(FeedSource.TFNET, "8126", "Loco", version = "1.3"),
        RemoteFile("2", "loco.7z", "https://example.invalid/loco.7z", size = 14_200_000, sha256 = "ab"),
    )

    @Test
    fun `status prefers broken over missing dependency over update`() {
        val broken = mod("broken", problems = listOf("mod.json missing"))
        val needy = mod("needy")
        val loco = mod("loco")
        val data = LibraryData(
            mods = listOf(broken, needy, loco),
            records = mapOf("loco" to record("loco"), "needy" to record("needy")),
            updates = listOf(update("loco"), update("needy")),
            missing = listOf(MissingDependency(needy, ModReference("common_api", revisionMin = 2), "Common API")),
        )

        assertEquals(StatusKind.ERROR, data.status(broken).kind)
        assertEquals("Missing dependency", data.status(needy).label)
        assertEquals("Needs Common API, revision 2 or later. It is not installed.", data.status(needy).note)
        assertEquals("Update 1.2 → 1.3", data.status(loco).label)
        assertEquals(StatusKind.UPDATE, data.status(loco).kind)
    }

    @Test
    fun `local mods tell how they were installed`() {
        val fromFeed = mod("a")
        val fromArchive = mod("b")
        val byHand = mod("c")
        val data = LibraryData(
            mods = listOf(fromFeed, fromArchive, byHand),
            records = mapOf("a" to record("a"), "b" to record("b", source = null)),
        )

        assertEquals("Up to date", data.status(fromFeed).label)
        assertEquals("From archive", data.status(fromArchive).label)
        assertEquals("Added by hand", data.status(byHand).label)
        assertEquals("transportfever.net · tfnet:8126", data.sourceText(fromFeed))
        assertEquals("Installed from archive", data.sourceText(fromArchive))
    }

    @Test
    fun `changed outside wins over an update and becomes a problem`() {
        val loco = mod("loco")
        val data = LibraryData(
            mods = listOf(loco),
            records = mapOf("loco" to record("loco")),
            changedOutside = listOf(ChangedOutside(record("loco"), loco)),
        )

        assertEquals("Changed outside", data.status(loco).label)
        val problem = data.problems().single()
        assertEquals(ProblemKind.CHANGED_OUTSIDE, problem.kind)
        assertEquals(ProblemAction.OpenFolder(loco.folder), problem.action)
    }

    @Test
    fun `a removed folder can be installed again`() {
        val data = LibraryData(changedOutside = listOf(ChangedOutside(record("gone"), null)))

        assertEquals(ProblemAction.Reinstall("tfnet:8126"), data.problems().single().action)
    }

    @Test
    fun `mod io mods are compared with the subscriptions`() {
        val subscribed = mod("111", ModLocation.MOD_IO, modId = "signals")
        val dropped = mod("222", ModLocation.MOD_IO, modId = "wagons")
        val subscriptions = ModIoSubscriptions(
            subscribed = listOf(ModIoMod(111, "Better Signals"), ModIoMod(333, "Not Downloaded")),
            installed = listOf(subscribed, dropped),
        )
        val data = LibraryData(mods = listOf(subscribed, dropped), modIo = subscriptions)

        assertEquals("Subscribed", data.status(subscribed).label)
        assertEquals("Better Signals", data.displayName(subscribed, null))
        assertEquals("Not subscribed", data.status(dropped).label)
        val problems = data.problems()
        assertEquals(listOf("No longer subscribed", "Subscribed, not downloaded yet"), problems.map { it.title })
        assertTrue(problems.all { it.severity == Severity.INFO })
        assertTrue("All mod.io subscriptions downloaded" !in data.passedChecks())
    }

    @Test
    fun `missing dependency problem offers a search`() {
        val needy = mod("timetable")
        val data = LibraryData(
            mods = listOf(needy),
            missing = listOf(MissingDependency(needy, ModReference("common_api", revisionMin = 2), "Common API")),
        )

        val problem = data.problems().single()
        assertEquals(ProblemAction.Find("Common API"), problem.action)
        assertEquals("timetable → common_api ≥ 2", problem.code)
        assertEquals("timetable needs Common API, revision 2 or later. No installed mod has that mod id.", problem.text.text)
    }

    @Test
    fun `feed issues that need a sign-in point to the settings`() {
        val data = LibraryData()
        val problems = data.problems(
            mapOf(
                FeedSource.MODWERKSTATT to FeedIssue("HTTP 401", signInRefused = true),
                FeedSource.TFNET to FeedIssue("timeout"),
            ),
        )

        assertEquals(listOf(ProblemAction.SignIn, ProblemAction.RefreshFeeds), problems.map { it.action })
    }

    @Test
    fun `passed checks list what found nothing`() {
        val data = LibraryData(mods = listOf(mod("a")))

        assertEquals(
            listOf(
                "No two mods share a mod id",
                "Every mod.json could be read",
                "Every required dependency is installed",
                "No mod changed outside Shunter",
            ),
            data.passedChecks(),
        )
    }
}
