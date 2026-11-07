package ch.lightspots.shunter.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.lightspots.shunter.app.AppModel
import ch.lightspots.shunter.app.LibraryData
import ch.lightspots.shunter.app.Problem
import ch.lightspots.shunter.app.ProblemAction
import ch.lightspots.shunter.app.ProblemKind
import ch.lightspots.shunter.app.Screen
import ch.lightspots.shunter.app.Severity
import ch.lightspots.shunter.app.date
import ch.lightspots.shunter.app.humanSize
import ch.lightspots.shunter.core.AvailableUpdate
import ch.lightspots.shunter.core.mod.ModLocation

@Composable
fun UpdatesScreen(model: AppModel) {
    val c = colors
    val library = model.library
    val problems = model.problems
    ScrollPage {
        ScreenHeader("Updates", "For mods installed with Shunter, plus anything that keeps a mod from loading.") {
            AppButton(
                if (model.feedsLoading) "Checking…" else "Check again",
                model::refreshFeeds,
                icon = Icons.refresh,
                enabled = !model.feedsLoading,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Available updates", Modifier.weight(1f))
                if (library.updates.size > 1) {
                    AppButton(
                        "Update all",
                        model::updateAll,
                        style = ButtonStyle.PRIMARY,
                        enabled = library.updates.any { it.remote.ref !in model.operations },
                    )
                }
            }
            if (library.updates.isEmpty()) {
                Text(
                    when {
                        model.feeds.isEmpty() && model.feedsLoading -> "Loading the mod lists…"
                        model.feeds.isEmpty() -> "The mod lists could not be loaded, so updates are unknown."
                        else -> "All mods installed with Shunter are up to date."
                    },
                    style = Type.body,
                    color = c.textMuted,
                )
            } else {
                Panel {
                    library.updates.forEachIndexed { i, u ->
                        if (i > 0) Divider()
                        UpdateRow(model, library, u)
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Problems")
            if (problems.isEmpty()) {
                Text("No problems found.", style = Type.body, color = c.textMuted)
            } else {
                Panel {
                    problems.forEachIndexed { i, p ->
                        if (i > 0) Divider()
                        ProblemRow(model, p)
                    }
                }
            }
        }

        val passed = library.passedChecks()
        if (passed.isNotEmpty() && library.mods.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Checked, no problem")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    passed.forEach { check ->
                        Row(
                            Modifier
                                .clip(CircleShape)
                                .background(c.surface)
                                .border(1.dp, c.border, CircleShape)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.check, null, Modifier.size(14.dp), tint = c.primary)
                            Text(check, style = Type.small, color = c.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateRow(model: AppModel, library: LibraryData, u: AvailableUpdate) {
    val c = colors
    val mod = library.mods.firstOrNull { it.location == ModLocation.LOCAL && it.folderName == u.record.folderName }
    val name = mod?.let { library.displayName(it, model.language) } ?: u.remote.name
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = Type.item, color = c.heading)
                Badge(u.remote.source.label, u.remote.source.tone())
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(LibraryData.installedVersion(u), c.neutral, pill = false)
                Icon(Icons.arrowRight, null, Modifier.size(16.dp).semantics { contentDescription = "to" }, tint = c.textMuted)
                Badge(LibraryData.newVersion(u), c.info, pill = false)
                val details = listOfNotNull(date(u.file.changedAt ?: u.remote.updatedAt)?.let { "updated $it" }, humanSize(u.file.size))
                if (details.isNotEmpty()) Text(details.joinToString(" · "), style = Type.small, color = c.textMuted)
            }
            Text(u.record.folderName, style = Type.mono, color = c.textMuted)
        }
        Column(Modifier.width(240.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val op = model.operations[u.remote.ref]
            if (op != null) {
                OperationProgress(op)
            } else {
                AppButton("Update", { model.update(u) })
                Text("The current version goes to the backups first.", style = Type.caption, color = c.textMuted, textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
private fun ProblemRow(model: AppModel, problem: Problem) {
    val c = colors
    val tone = when (problem.severity) {
        Severity.ERROR -> c.error
        Severity.WARN -> c.warn
        Severity.INFO -> c.info
    }
    val icon = when (problem.kind) {
        ProblemKind.MISSING_DEPENDENCY -> Icons.brokenLink
        ProblemKind.CHANGED_OUTSIDE -> Icons.folderWarning
        ProblemKind.DUPLICATE -> Icons.copies
        else -> Icons.warning
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(tone.bg), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(18.dp), tint = tone.fg)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(problem.title, style = Type.item, color = c.heading)
            Text(problem.text, style = Type.body, color = c.textSecondary)
            problem.code?.let { Text(it, style = Type.mono, color = c.textMuted) }
        }
        when (val action = problem.action) {
            is ProblemAction.Find -> AppButton("Find ${action.query}", { model.find(action.query) })

            is ProblemAction.OpenFolder -> AppButton("Open folder", { model.openFolder(action.path) })

            is ProblemAction.Reinstall -> AppButton(
                "Install again",
                { model.reinstall(action.ref) },
                enabled =
                action.ref !in model.operations,
            )

            ProblemAction.SignIn -> AppButton("Settings", { model.screen = Screen.SETTINGS })

            ProblemAction.RefreshFeeds -> AppButton("Try again", model::refreshFeeds, enabled = !model.feedsLoading)

            null -> Unit
        }
    }
}
