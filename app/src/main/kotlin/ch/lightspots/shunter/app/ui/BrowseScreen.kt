package ch.lightspots.shunter.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.lightspots.shunter.app.AppModel
import ch.lightspots.shunter.app.BrowseSort
import ch.lightspots.shunter.app.BrowseSource
import ch.lightspots.shunter.app.Screen
import ch.lightspots.shunter.app.date
import ch.lightspots.shunter.app.humanSize
import ch.lightspots.shunter.core.feed.FeedSource
import ch.lightspots.shunter.core.feed.RemoteMod
import ch.lightspots.shunter.core.modio.ModIoMod

/** One search result: an entry in a site's mod list, or a mod on mod.io. */
private sealed interface BrowseItem {
    val key: String
    val name: String
    val updatedAt: Long?

    data class Feed(val mod: RemoteMod) : BrowseItem {
        override val key get() = mod.ref
        override val name get() = mod.name
        override val updatedAt get() = mod.updatedAt
    }

    data class ModIo(val mod: ModIoMod) : BrowseItem {
        override val key get() = "modio:${mod.id}"
        override val name get() = mod.name
        override val updatedAt get() = mod.updatedAt
    }
}

@Composable
fun BrowseScreen(model: AppModel) {
    val c = colors
    val query = model.browseQuery.trim()
    val source = model.browseSource
    val signedInModIo = model.modIoLogin != null

    Debounced(Triple(query, source.includesModIo, signedInModIo)) {
        if (source.includesModIo && signedInModIo) model.searchModIo(query)
    }

    val q = query.lowercase()
    val feedItems = model.feeds.values
        .filter { source.includes(it.source) }
        .flatMap { it.mods }
        .filter { m -> q.isEmpty() || listOfNotNull(m.name, m.author).plus(m.tags).any { it.lowercase().contains(q) } }
        .map { BrowseItem.Feed(it) }
    val modIoItems = if (source.includesModIo && signedInModIo) model.modIoResults.map { BrowseItem.ModIo(it) } else emptyList()
    val items = (feedItems + modIoItems).let { all ->
        when (model.browseSort) {
            BrowseSort.UPDATED -> all.sortedByDescending { it.updatedAt ?: 0 }
            BrowseSort.NAME -> all.sortedBy { it.name.lowercase() }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ScreenHeader("Browse", "Mods on transportfever.net, modwerkstatt.com and mod.io.") {
            AppButton(
                if (model.feedsLoading) "Refreshing…" else "Refresh lists",
                model::refreshFeeds,
                icon = Icons.refresh,
                enabled = !model.feedsLoading,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextInput(
                model.browseQuery,
                { model.browseQuery = it },
                "Search mods",
                Modifier.widthIn(min = 240.dp, max = 440.dp).weight(1f, fill = false),
                height = 44.dp,
                leading = Icons.search,
            )
            Segmented(BrowseSource.entries, source, { it.label }, { model.browseSource = it })
            SortMenu(model)
        }

        val counted = buildString {
            append("${items.size} mods")
            if (query.isNotEmpty()) append(" match “$query”")
            if (modIoItems.isNotEmpty() && model.modIoResultsTotal > modIoItems.size) {
                append(" (the first ${modIoItems.size} of ${model.modIoResultsTotal} on mod.io)")
            }
            if (model.modIoSearching) append(" · searching mod.io…")
        }
        Text(counted, style = Type.small, color = c.textMuted)

        val notes = buildList {
            model.feedIssues.filterKeys { source.includes(it) }.forEach { (s, issue) ->
                add("${s.label}: ${issue.message}" to if (issue.signInRefused) "Sign in" else null)
            }
            if (source.includesModIo && !signedInModIo) add("Sign in to mod.io in Settings to search the Mod Hub here." to "Settings")
            if (source.includesModIo && signedInModIo) model.modIoSearchError?.let { add("mod.io: $it" to null) }
        }
        notes.forEach { (text, action) ->
            Banner(text, action?.let { it to { model.screen = Screen.SETTINGS } })
        }

        Panel(Modifier.weight(1f, fill = false)) {
            if (items.isEmpty()) {
                Text(
                    if (model.feedsLoading || model.modIoSearching) "Loading…" else "Nothing found.",
                    Modifier.padding(20.dp),
                    style = Type.body,
                    color = c.textMuted,
                )
            }
            LazyColumn {
                items(items, key = { it.key }) { item ->
                    when (item) {
                        is BrowseItem.Feed -> FeedRow(model, item.mod)
                        is BrowseItem.ModIo -> ModIoRow(model, item.mod)
                    }
                    Divider()
                }
            }
        }
    }
}

@Composable
private fun SortMenu(model: AppModel) {
    var open by rememberState(false)
    Box {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sort", style = Type.small, color = colors.textMuted)
            AppButton(model.browseSort.label, { open = true }, icon = Icons.chevronDown)
        }
        DropdownMenu(open, { open = false }, containerColor = colors.surface) {
            BrowseSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(sort.label, style = Type.body, color = colors.text) },
                    onClick = {
                        model.browseSort = sort
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ResultRow(
    name: String,
    image: ImageBitmap?,
    tone: Tone,
    siteLabel: String,
    meta: String,
    summary: String?,
    deps: String?,
    action: @Composable () -> Unit,
) {
    val c = colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Thumbnail(name, image, tone, 96.dp, 54.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, Modifier.weight(1f, fill = false), style = Type.item, color = c.heading)
                Badge(siteLabel, tone)
            }
            if (meta.isNotEmpty()) Text(meta, style = Type.small, color = c.textMuted)
            summary?.let { Text(it, style = Type.small, color = c.textSecondary, maxLines = 2) }
            deps?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.link, null, Modifier.size(14.dp), tint = c.textSecondary)
                    Text(it, style = Type.small, color = c.textSecondary)
                }
            }
        }
        Column(Modifier.width(220.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            action()
        }
    }
}

@Composable
private fun FeedRow(model: AppModel, mod: RemoteMod) {
    val library = model.library
    val size = mod.files.takeIf { files -> files.isNotEmpty() && files.all { it.size != null } }?.sumOf { it.size!! }
    val extensions = mod.files.map {
        it.fileName.substringAfterLast('.', "").lowercase()
    }.filter { it.isNotEmpty() && it != "zip" }.distinct()
    val meta = listOfNotNull(
        mod.author,
        mod.version?.let { if (it.startsWith("rev ")) it.replace("rev ", "revision ") else "version $it" },
        date(mod.updatedAt)?.let { "updated $it" },
        humanSize(size),
    ).plus(extensions.map { ".$it" }).joinToString(" · ")
    val deps = mod.dependencies.filter { it.required }.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Requires ") { dep ->
        val installed = dep.remoteId?.let { "${mod.source.id}:$it" in library.installedRefs } == true
        (dep.name ?: "?") + if (installed) " (installed)" else ""
    }
    ResultRow(
        mod.name,
        rememberRemoteImage(mod.imageUrl, model.manager.images, 192),
        mod.source.tone(),
        mod.source.label,
        meta,
        mod.tags.takeIf {
            it.isNotEmpty()
        }?.joinToString(),
        deps,
    ) {
        val op = model.operations[mod.ref]
        when {
            op != null -> OperationProgress(op)

            mod.ref in library.installedRefs && mod.ref !in library.updateRefs -> Done("Installed")

            else -> {
                val isUpdate = mod.ref in library.updateRefs
                AppButton(
                    if (isUpdate) "Update" else "Install",
                    { model.install(mod) },
                    Modifier.widthIn(min = 120.dp),
                    style = ButtonStyle.PRIMARY,
                    enabled = model.canInstall && mod.files.isNotEmpty(),
                )
                Hint(
                    when {
                        mod.files.isEmpty() -> "No file on ${mod.source.label} yet"
                        !model.canInstall -> "The game’s mod folder was not found"
                        isUpdate -> "The current version goes to the backups first"
                        mod.source == FeedSource.MODWERKSTATT -> "No checksum on this site, size is checked"
                        else -> "Size and SHA-256 are checked"
                    },
                )
            }
        }
    }
}

@Composable
private fun ModIoRow(model: AppModel, mod: ModIoMod) {
    val meta = listOfNotNull(
        mod.author,
        mod.version?.let { "version $it" },
        mod.subscribers?.let { "$it subscribers" },
        humanSize(mod.fileSize),
    ).joinToString(" · ")
    ResultRow(mod.name, rememberRemoteImage(mod.logoUrl, model.manager.images, 192), colors.modIo, "mod.io", meta, mod.summary, null) {
        val op = model.operations["modio:${mod.id}"]
        val subscribed = model.library.modIo?.subscribed?.any { it.id == mod.id } == true
        when {
            op != null -> OperationProgress(op)

            subscribed -> Done("Subscribed")

            else -> {
                AppButton("Subscribe", { model.subscribe(mod) }, Modifier.widthIn(min = 120.dp))
                Hint("The game downloads it on its next start")
            }
        }
    }
}

@Composable
private fun Done(text: String) {
    Row(
        Modifier.heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.check, null, Modifier.size(16.dp), tint = colors.local.fg)
        Text(text, style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = colors.local.fg)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = Type.caption, color = colors.textMuted, textAlign = TextAlign.End)
}
