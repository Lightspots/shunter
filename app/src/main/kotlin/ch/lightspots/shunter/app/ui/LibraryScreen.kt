package ch.lightspots.shunter.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lightspots.shunter.app.AppModel
import ch.lightspots.shunter.app.Launcher
import ch.lightspots.shunter.app.LibraryData
import ch.lightspots.shunter.app.Previews
import ch.lightspots.shunter.app.Screen
import ch.lightspots.shunter.app.initials
import ch.lightspots.shunter.app.revisionText
import ch.lightspots.shunter.core.mod.InstalledMod
import ch.lightspots.shunter.core.mod.ModLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Frame

private val LOCATION_ORDER = listOf(ModLocation.LOCAL, ModLocation.MOD_IO, ModLocation.STAGING)

@Composable
fun LibraryScreen(model: AppModel) {
    val library = model.library
    val query = model.libraryQuery.trim().lowercase()
    val shown = library.mods
        .filter { model.libraryFilter == null || it.location == model.libraryFilter }
        .filter {
            query.isEmpty() || library.displayName(it, model.language).lowercase().contains(query) ||
                it.folderName.lowercase().contains(query)
        }
        .sortedWith(compareBy({ LOCATION_ORDER.indexOf(it.location) }, { library.displayName(it, model.language).lowercase() }))
    val selected = shown.firstOrNull { it.folder == model.selectedFolder } ?: shown.firstOrNull()
    var removing by rememberState<InstalledMod?>(null)

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ScreenHeader("Library", "Mods the game sees: yours, the Mod Hub’s and your staging area.") {
            AppButton(if (model.scanning) "Scanning…" else "Rescan", model::rescan, icon = Icons.refresh, enabled = !model.scanning)
            AppButton(
                "Install archive…",
                { Launcher.chooseArchive(Frame.getFrames().firstOrNull { it.isActive })?.let(model::installArchive) },
                style = ButtonStyle.PRIMARY,
                icon = Icons.download,
                enabled = model.canInstall && model.operations.keys.none { it.startsWith("archive:") },
            )
        }

        if (!model.canInstall) {
            Banner(
                "The Transport Fever 3 mod folder was not found. Start the game once, then rescan. " +
                    "Until then Shunter cannot install mods.",
            )
        }
        library.missing.firstOrNull()?.let { m ->
            val more = library.missing.size - 1
            Banner(
                "${library.displayName(m.mod, model.language)} needs ${m.displayName ?: m.dependency.modId}" +
                    m.dependency.revisionText().takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty() +
                    (if (m.wrongRevision != null) ", the installed revision does not fit." else ", which is not installed.") +
                    if (more > 0) " $more more missing." else "",
                action = "Show problems" to { model.screen = Screen.UPDATES },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextInput(
                model.libraryQuery,
                { model.libraryQuery = it },
                "Filter by name or folder",
                Modifier.widthIn(min = 220.dp, max = 360.dp).weight(1f, fill = false),
                leading = Icons.search,
                description = "Filter installed mods",
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip("All ${library.count(null)}", model.libraryFilter == null) { model.libraryFilter = null }
                FilterChip("Local ${library.count(ModLocation.LOCAL)}", model.libraryFilter == ModLocation.LOCAL) {
                    model.libraryFilter = ModLocation.LOCAL
                }
                FilterChip("mod.io ${library.count(ModLocation.MOD_IO)}", model.libraryFilter == ModLocation.MOD_IO) {
                    model.libraryFilter = ModLocation.MOD_IO
                }
                FilterChip("Staging ${library.count(ModLocation.STAGING)}", model.libraryFilter == ModLocation.STAGING) {
                    model.libraryFilter = ModLocation.STAGING
                }
            }
        }

        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Panel(Modifier.weight(1f)) {
                TableRow(header = true) {
                    Box(Modifier.width(40.dp))
                    HeaderText("Mod", Modifier.weight(1f))
                    HeaderText("Location", Modifier.width(92.dp))
                    HeaderText("Revision", Modifier.width(72.dp))
                    HeaderText("Status", Modifier.width(170.dp))
                }
                Divider()
                if (shown.isEmpty()) {
                    Text(
                        when {
                            model.scanning -> "Scanning the mod folders…"
                            library.mods.isEmpty() -> "No mods found. Install one from Browse or from an archive."
                            else -> "No mod matches the filter."
                        },
                        Modifier.padding(20.dp),
                        style = Type.body,
                        color = colors.textMuted,
                    )
                }
                LazyColumn {
                    items(shown, key = { it.folder.toString() }) { mod ->
                        ModRow(model, library, mod, mod == selected) { model.selectedFolder = mod.folder }
                    }
                }
            }
            if (selected != null) {
                ModDetails(model, library, selected, onRemove = { removing = it }, modifier = Modifier.width(340.dp))
            }
        }
    }

    removing?.let { mod ->
        val name = library.displayName(mod, model.language)
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove $name?", style = Type.section, color = colors.heading) },
            text = {
                Text(
                    "The folder ${mod.folderName} is moved to Shunter’s backups, so you can put it back by hand." +
                        (mod.manifest?.severityRemove?.let { " The mod says removing it affects savegames ($it)." } ?: ""),
                    style = Type.body,
                    color = colors.textSecondary,
                )
            },
            confirmButton = {
                AppButton("Remove", {
                    removing = null
                    model.remove(mod)
                }, style = ButtonStyle.DANGER)
            },
            dismissButton = { AppButton("Cancel", { removing = null }) },
            containerColor = colors.surface,
        )
    }
}

@Composable
private fun TableRow(header: Boolean = false, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
private fun HeaderText(text: String, modifier: Modifier) {
    Text(text.uppercase(), modifier, style = Type.columnHeader, color = colors.textMuted)
}

@Composable
private fun ModRow(model: AppModel, library: LibraryData, mod: InstalledMod, isSelected: Boolean, onClick: () -> Unit) {
    val c = colors
    val status = library.status(mod)
    val name = library.displayName(mod, model.language)
    Column {
        TableRow(
            modifier = Modifier
                .background(if (isSelected) c.selectedRow else c.surface)
                .clickable(role = Role.Button, onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand)
                .semantics { selected = isSelected },
        ) {
            Thumbnail(name, mod.previewImage, status.kind.tone(), 40.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = Type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(mod.folderName, style = Type.mono, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.width(92.dp)) { Badge(mod.location.label, mod.location.tone()) }
            Text(mod.manifest?.revision?.toString() ?: "–", Modifier.width(72.dp), style = Type.body, color = c.textSecondary)
            Box(Modifier.width(170.dp)) { Badge(status.label, status.kind.tone(), pill = false) }
        }
        Divider()
    }
}

@Composable
private fun ModDetails(model: AppModel, library: LibraryData, mod: InstalledMod, onRemove: (InstalledMod) -> Unit, modifier: Modifier) {
    val c = colors
    val status = library.status(mod)
    val name = library.displayName(mod, model.language)
    val text = mod.metadata?.localized(model.language)
    val update = library.updateFor(mod)
    val subscription = library.modIo?.subscriptionOf(mod)
    val pageUrl = library.pageUrl(mod) { ref -> model.findRemote(ref)?.pageUrl }

    Panel(modifier, padding = PaddingValues(20.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LargePreview(name, mod, status.kind.tone())
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                    Badge(mod.location.label, mod.location.tone())
                    Text(library.sourceText(mod), Modifier.align(Alignment.CenterVertically), style = Type.caption, color = c.textMuted)
                }
                Text(name, style = Type.detail, color = c.heading)
                val authors = mod.metadata?.authors.orEmpty().map { it.name }.ifEmpty { listOfNotNull(subscription?.author) }
                if (authors.isNotEmpty()) Text("by ${authors.joinToString()}", style = Type.body, color = c.textMuted)
            }
            (text?.summary ?: subscription?.summary)?.let { Text(it, style = Type.body, color = c.textSecondary) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Fact("Folder", mod.folderName, mono = true)
                Fact("Mod id", mod.modId ?: "–", mono = true)
                Fact("Revision", mod.manifest?.revision?.toString() ?: "–")
                Fact(
                    "Requires",
                    mod.manifest?.dependencies.orEmpty().filter { !it.optional }.joinToString { d ->
                        (d.displayName ?: d.mod.modId) + d.mod.revisionText().takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
                    }.ifEmpty { "none" },
                )
            }
            status.note?.let { note ->
                val tone = status.kind.tone()
                Text(
                    note,
                    Modifier.fillMaxWidth().clip(
                        RoundedCornerShape(8.dp),
                    ).background(tone.bg).padding(horizontal = 12.dp, vertical = 10.dp),
                    style = Type.small,
                    color = tone.fg,
                )
            }
            val op = update?.let { model.operations[it.remote.ref] }
            if (op != null) {
                OperationProgress(op)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (update != null && op == null) {
                    AppButton("Update to ${LibraryData.newVersion(update)}", { model.update(update) }, style = ButtonStyle.PRIMARY)
                }
                AppButton("Open folder", { model.openFolder(mod.folder) })
                if (pageUrl != null) AppButton("Open page", { model.openUrl(pageUrl) })
                if (mod.location ==
                    ModLocation.LOCAL
                ) {
                    AppButton("Remove", { onRemove(mod) }, style = ButtonStyle.DANGER, enabled = op == null)
                }
                val modIoId = mod.folderName.toLongOrNull()
                if (mod.location == ModLocation.MOD_IO && subscription != null && modIoId != null) {
                    AppButton(
                        "Unsubscribe",
                        { model.unsubscribe(modIoId, name) },
                        enabled = "modio:$modIoId" !in model.operations,
                    )
                }
            }
            Text(
                when (mod.location) {
                    ModLocation.LOCAL -> "Remove moves the folder to Shunter’s backups."
                    ModLocation.MOD_IO -> "The game’s Mod Hub manages this folder. Shunter only changes the subscription."
                    ModLocation.STAGING -> "Your staging area is read only for Shunter."
                },
                style = Type.caption,
                color = c.textMuted,
            )
        }
    }
}

@Composable
private fun LargePreview(name: String, mod: InstalledMod, tone: Tone) {
    val image by produceState(Previews.cached(mod.previewImage, 640), mod.previewImage) {
        if (value == null) value = withContext(Dispatchers.IO) { Previews.load(mod.previewImage, 640) }
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(tone.bg),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, "Preview of $name", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(initials(name), style = Type.title.copy(fontSize = 32.sp), color = tone.fg)
                Text("No preview image", style = Type.caption.copy(fontWeight = FontWeight.Medium), color = tone.fg)
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, mono: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.width(72.dp), style = Type.small, color = colors.textMuted)
        Text(value, Modifier.weight(1f), style = if (mono) Type.mono.copy(fontSize = 13.sp) else Type.small, color = colors.text)
    }
}

/** A warning strip across the screen, with an optional link. */
@Composable
fun Banner(text: String, action: Pair<String, () -> Unit>? = null) {
    val c = colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.warn.bg)
            .border(1.dp, c.warnBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.warning, null, Modifier.size(18.dp), tint = c.warn.fg)
        Text(text, Modifier.weight(1f), style = Type.body, color = c.warn.fg)
        if (action != null) Link(action.first, action.second, color = c.warn.fg)
    }
}
