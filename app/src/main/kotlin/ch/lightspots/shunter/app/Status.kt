package ch.lightspots.shunter.app

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import ch.lightspots.shunter.core.AvailableUpdate
import ch.lightspots.shunter.core.ChangedOutside
import ch.lightspots.shunter.core.MissingDependency
import ch.lightspots.shunter.core.feed.FeedSource
import ch.lightspots.shunter.core.install.InstallRecord
import ch.lightspots.shunter.core.mod.InstalledMod
import ch.lightspots.shunter.core.mod.ModLocation
import ch.lightspots.shunter.core.modio.ModIoSubscriptions
import java.nio.file.Path

/** What the mod folders hold and what is known about them; everything the Library and Updates screens show. */
data class LibraryData(
    val mods: List<InstalledMod> = emptyList(),
    /** Install records of the local mods folder, by folder name. */
    val records: Map<String, InstallRecord> = emptyMap(),
    val updates: List<AvailableUpdate> = emptyList(),
    val changedOutside: List<ChangedOutside> = emptyList(),
    val missing: List<MissingDependency> = emptyList(),
    val duplicates: Map<String, List<InstalledMod>> = emptyMap(),
    /** Null when not signed in to mod.io or the subscriptions could not be loaded. */
    val modIo: ModIoSubscriptions? = null,
) {
    private val updatesByFolder = updates.associateBy { it.record.folderName }
    private val changedFolders = changedOutside.map { it.record.folderName }.toSet()
    private val missingByFolder = missing.groupBy { it.mod.folder }

    /** Feed references (`tfnet:8126`) of mods installed with Shunter. */
    val installedRefs: Set<String> = records.values.mapNotNull { it.origin.ref }.toSet()

    val updateRefs: Set<String> = updates.map { it.remote.ref }.toSet()

    fun count(location: ModLocation?): Int = if (location == null) mods.size else mods.count { it.location == location }

    fun updateFor(mod: InstalledMod): AvailableUpdate? = if (mod.location == ModLocation.LOCAL) updatesByFolder[mod.folderName] else null

    fun recordFor(mod: InstalledMod): InstallRecord? = if (mod.location == ModLocation.LOCAL) records[mod.folderName] else null

    fun missingFor(mod: InstalledMod): List<MissingDependency> = missingByFolder[mod.folder].orEmpty()

    /** mod.io packages have no `_metadata`, so their name comes from the subscription. */
    fun displayName(mod: InstalledMod, language: String?): String = if (mod.metadata == null && mod.location == ModLocation.MOD_IO) {
        modIo?.subscriptionOf(mod)?.name ?: mod.displayName(language)
    } else {
        mod.displayName(language)
    }

    fun status(mod: InstalledMod): ModStatus {
        if (mod.problems.isNotEmpty()) return ModStatus(StatusKind.ERROR, "Cannot be read", mod.problems.joinToString("; "))
        missingFor(mod).firstOrNull()?.let { m ->
            return ModStatus(StatusKind.ERROR, "Missing dependency", missingText(m))
        }
        val record = recordFor(mod)
        if (record != null && record.folderName in changedFolders) {
            return ModStatus(
                StatusKind.WARN,
                "Changed outside",
                "The folder was replaced by hand after Shunter installed it, so no updates are offered for it.",
            )
        }
        mod.modId?.let { duplicates[it] }?.let { same ->
            val others = same.filter { it.folder != mod.folder }.joinToString { "${it.location.label}/${it.folderName}" }
            return ModStatus(
                StatusKind.WARN,
                "Duplicate mod id",
                "Mod id ${mod.modId} is also in $others. The game loads only one of them.",
            )
        }
        updateFor(mod)?.let { u ->
            val site = u.remote.source.label
            val size = humanSize(u.file.size)?.let { "$it, " }.orEmpty()
            val check = if (u.file.sha256 != null) "checksum verified on download" else "size checked on download"
            return ModStatus(
                StatusKind.UPDATE,
                "Update ${installedVersion(u)} → ${newVersion(u)}",
                "Version ${newVersion(u)} is available on $site ($size$check).",
            )
        }
        return when (mod.location) {
            ModLocation.MOD_IO -> when {
                modIo == null -> ModStatus(StatusKind.OK, "Mod Hub")

                modIo.subscriptionOf(mod) != null -> ModStatus(StatusKind.OK, "Subscribed")

                else -> ModStatus(
                    StatusKind.WARN,
                    "Not subscribed",
                    "You are no longer subscribed. The game removes it on its next start.",
                )
            }

            ModLocation.STAGING -> ModStatus(StatusKind.OK, "Own mod")

            ModLocation.LOCAL -> when {
                record?.origin?.source != null -> ModStatus(StatusKind.OK, "Up to date")
                record != null -> ModStatus(StatusKind.OK, "From archive")
                else -> ModStatus(StatusKind.OK, "Added by hand")
            }
        }
    }

    /** Where the mod came from, e.g. "transportfever.net · tfnet:8126". */
    fun sourceText(mod: InstalledMod): String = when (mod.location) {
        ModLocation.MOD_IO -> "Mod Hub · mod.io ${mod.folderName}"

        ModLocation.STAGING -> "Your staging area"

        ModLocation.LOCAL -> {
            val origin = recordFor(mod)?.origin
            when {
                origin?.source != null -> "${origin.source!!.label} · ${origin.ref}"
                recordFor(mod) != null -> "Installed from archive"
                else -> "Not installed with Shunter"
            }
        }
    }

    /** The page on the mod's site: from `modinfo.json`, else from the feed it was installed from. */
    fun pageUrl(mod: InstalledMod, feedPage: (String) -> String?): String? = mod.metadata?.url ?: recordFor(mod)?.origin?.ref?.let(feedPage)
        ?: modIo?.subscriptionOf(mod)?.profileUrl

    /** What keeps mods from loading or Shunter from updating them, most serious first. */
    fun problems(feedIssues: Map<FeedSource, FeedIssue> = emptyMap(), modIoIssue: String? = null, language: String? = null): List<Problem> =
        buildList {
            for (mod in mods.filter { it.problems.isNotEmpty() }) {
                add(
                    Problem(
                        key = "broken:${mod.folder}",
                        severity = Severity.ERROR,
                        kind = ProblemKind.BROKEN,
                        title = "Mod cannot be read",
                        text = AnnotatedString("${mod.location.label}/${mod.folderName}: ${mod.problems.joinToString("; ")}"),
                        code = mod.folderName,
                        action = ProblemAction.OpenFolder(mod.folder),
                    ),
                )
            }
            for (m in missing) {
                val depName = m.displayName ?: m.dependency.modId
                add(
                    Problem(
                        key = "missing:${m.mod.folder}:${m.dependency.modId}",
                        severity = Severity.ERROR,
                        kind = ProblemKind.MISSING_DEPENDENCY,
                        title = if (m.wrongRevision != null) "Dependency has the wrong revision" else "Missing dependency",
                        text = buildAnnotatedString {
                            bold(displayName(m.mod, language))
                            append(" needs ")
                            bold(depName)
                            m.dependency.revisionText().takeIf { it.isNotEmpty() }?.let { append(", $it") }
                            append(". ")
                            append(
                                if (m.wrongRevision != null) {
                                    "The installed one has revision ${m.wrongRevision}."
                                } else {
                                    "No installed mod has that mod id."
                                },
                            )
                        },
                        code = "${m.mod.modId ?: m.mod.folderName} → ${m.dependency.shortText()}",
                        action = ProblemAction.Find(depName),
                    ),
                )
            }
            for (c in changedOutside) {
                val record = c.record
                val site = record.origin.source?.label
                add(
                    Problem(
                        key = "changed:${record.folderName}",
                        severity = Severity.WARN,
                        kind = ProblemKind.CHANGED_OUTSIDE,
                        title = "Changed outside Shunter",
                        text = buildAnnotatedString {
                            bold(c.current?.displayName(language) ?: record.folderName)
                            if (c.current == null) {
                                append(" was removed by hand after Shunter installed it")
                                site?.let { append(" from $it") }
                                append(".")
                            } else {
                                append(" was replaced by hand after Shunter installed it")
                                site?.let { append(" from $it") }
                                append(". Which version is in the folder is unknown, so Shunter offers no updates for it.")
                            }
                        },
                        code = record.folderName,
                        action = when {
                            c.current != null -> ProblemAction.OpenFolder(c.current!!.folder)
                            record.origin.ref != null -> ProblemAction.Reinstall(record.origin.ref!!)
                            else -> null
                        },
                    ),
                )
            }
            for ((modId, same) in duplicates) {
                add(
                    Problem(
                        key = "duplicate:$modId",
                        severity = Severity.WARN,
                        kind = ProblemKind.DUPLICATE,
                        title = "Same mod id twice",
                        text = buildAnnotatedString {
                            append("Mod id ")
                            bold(modId)
                            append(" is in ${same.size} folders. The game loads only one of them.")
                        },
                        code = same.joinToString("  ") { "${it.location.label}/${it.folderName}" },
                        action = same.firstOrNull { it.location == ModLocation.LOCAL }?.let { ProblemAction.OpenFolder(it.folder) },
                    ),
                )
            }
            for ((source, issue) in feedIssues) {
                add(
                    Problem(
                        key = "feed:${source.id}",
                        severity = Severity.WARN,
                        kind = ProblemKind.FEED,
                        title = "Could not refresh the ${source.label} list",
                        text = AnnotatedString(
                            issue.message + if (issue.signInRefused) " The site no longer accepts your sign-in." else "",
                        ),
                        code = null,
                        action = if (issue.signInRefused) ProblemAction.SignIn else ProblemAction.RefreshFeeds,
                    ),
                )
            }
            modIoIssue?.let {
                add(
                    Problem(
                        "modio",
                        Severity.WARN,
                        ProblemKind.FEED,
                        "Could not load your mod.io subscriptions",
                        AnnotatedString(it),
                        null,
                        ProblemAction.SignIn,
                    ),
                )
            }
            val notSubscribed = modIo?.notSubscribed.orEmpty()
            if (notSubscribed.isNotEmpty()) {
                add(
                    Problem(
                        key = "modio:notSubscribed",
                        severity = Severity.INFO,
                        kind = ProblemKind.MOD_IO,
                        title = "No longer subscribed",
                        text = AnnotatedString(
                            "${notSubscribed.joinToString { displayName(it, language) }}: the game removes " +
                                "${if (notSubscribed.size == 1) "it" else "them"} on its next start.",
                        ),
                        code = null,
                        action = null,
                    ),
                )
            }
            val notDownloaded = modIo?.notDownloaded.orEmpty()
            if (notDownloaded.isNotEmpty()) {
                add(
                    Problem(
                        key = "modio:notDownloaded",
                        severity = Severity.INFO,
                        kind = ProblemKind.MOD_IO,
                        title = "Subscribed, not downloaded yet",
                        text = AnnotatedString("${notDownloaded.joinToString { it.name }}: the game downloads them when it starts."),
                        code = null,
                        action = null,
                    ),
                )
            }
        }

    /** The checks that found nothing, for the "Checked, no problem" list. */
    fun passedChecks(): List<String> = buildList {
        if (duplicates.isEmpty()) add("No two mods share a mod id")
        if (mods.none { it.problems.isNotEmpty() }) add("Every mod.json could be read")
        if (missing.isEmpty()) add("Every required dependency is installed")
        if (changedOutside.isEmpty()) add("No mod changed outside Shunter")
        if (modIo != null && modIo.notDownloaded.isEmpty()) add("All mod.io subscriptions downloaded")
    }

    companion object {
        fun installedVersion(u: AvailableUpdate): String =
            u.record.origin.version ?: u.record.revision?.let { "rev $it" } ?: date(u.record.origin.remoteChangedAt) ?: "?"

        fun newVersion(u: AvailableUpdate): String = u.remote.version ?: date(u.file.changedAt) ?: "new file"

        fun missingText(m: MissingDependency): String {
            val name = m.displayName ?: m.dependency.modId
            val revision = m.dependency.revisionText().takeIf { it.isNotEmpty() }?.let { ", $it" }.orEmpty()
            return if (m.wrongRevision != null) {
                "Needs $name$revision. The installed one has revision ${m.wrongRevision}."
            } else {
                "Needs $name$revision. It is not installed."
            }
        }
    }
}

private fun AnnotatedString.Builder.bold(text: String) = withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text) }

enum class StatusKind { OK, UPDATE, WARN, ERROR }

data class ModStatus(val kind: StatusKind, val label: String, val note: String? = null)

/** Refreshing a feed failed; an older copy (or nothing) is shown. */
data class FeedIssue(val message: String, val signInRefused: Boolean = false)

enum class Severity { ERROR, WARN, INFO }

enum class ProblemKind { BROKEN, MISSING_DEPENDENCY, CHANGED_OUTSIDE, DUPLICATE, FEED, MOD_IO }

data class Problem(
    val key: String,
    val severity: Severity,
    val kind: ProblemKind,
    val title: String,
    val text: AnnotatedString,
    /** Folder names or mod ids, shown in a monospaced font. */
    val code: String?,
    val action: ProblemAction?,
)

sealed interface ProblemAction {
    /** Search the sites for the mod. */
    data class Find(val query: String) : ProblemAction

    data class OpenFolder(val path: Path) : ProblemAction

    /** Install again from the feed entry (`tfnet:8126`). */
    data class Reinstall(val ref: String) : ProblemAction

    data object SignIn : ProblemAction

    data object RefreshFeeds : ProblemAction
}
