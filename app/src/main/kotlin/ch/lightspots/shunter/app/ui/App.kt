package ch.lightspots.shunter.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lightspots.shunter.app.AppModel
import ch.lightspots.shunter.app.Message
import ch.lightspots.shunter.app.Screen
import ch.lightspots.shunter.app.StatusKind
import ch.lightspots.shunter.app.ago
import ch.lightspots.shunter.app.shortPath
import ch.lightspots.shunter.core.feed.FeedSource
import ch.lightspots.shunter.core.mod.ModLocation
import kotlinx.coroutines.delay
import java.time.Instant

@Composable
fun ShunterApp(model: AppModel) {
    Row(Modifier.fillMaxSize().background(colors.page)) {
        Sidebar(model)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (model.screen) {
                Screen.LIBRARY -> LibraryScreen(model)
                Screen.BROWSE -> BrowseScreen(model)
                Screen.UPDATES -> UpdatesScreen(model)
                Screen.SETTINGS -> SettingsScreen(model)
            }
            Messages(model, Modifier.align(Alignment.BottomEnd).padding(24.dp))
        }
    }
}

@Composable
private fun Sidebar(model: AppModel) {
    val c = colors
    Column(
        Modifier
            .width(232.dp)
            .fillMaxHeight()
            .background(c.nav)
            .drawBehind {
                // Only the edge towards the content, as in the design
                val x = size.width - 0.5.dp.toPx()
                drawLine(c.navBorder, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            }
            .padding(horizontal = 14.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(Icons.logo, null, Modifier.size(28.dp), colorFilter = ColorFilter.tint(c.accent))
            Column(Modifier.padding(start = 10.dp)) {
                Text("Shunter", style = Type.body.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold), color = Color.White)
                Text("Transport Fever 3 mods", style = Type.caption, color = c.navMuted)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NavItem(model, Screen.LIBRARY, Icons.library, count = model.library.mods.size.takeIf { it > 0 }?.toString())
            NavItem(model, Screen.BROWSE, Icons.search)
            NavItem(model, Screen.UPDATES, Icons.updates, badge = model.attentionCount.takeIf { it > 0 }?.toString())
            NavItem(model, Screen.SETTINGS, Icons.settings)
        }
        Box(Modifier.weight(1f))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.navCard).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Mods folder", style = Type.caption, color = c.navMuted)
            Text(
                model.manager.gamePaths.localMods?.let { shortPath(it.toString()) } ?: "not found",
                style = Type.mono,
                color = c.navCardText,
            )
            val now by produceState(Instant.now()) {
                while (true) {
                    delay(30_000)
                    value = Instant.now()
                }
            }
            Text(
                when {
                    model.feedsLoading -> "Refreshing the mod lists…"
                    else -> model.feedsFetchedAt?.let { "Mod lists from ${ago(it, now)}" } ?: "Mod lists not loaded"
                },
                style = Type.caption,
                color = c.navMuted,
            )
        }
    }
}

@Composable
private fun NavItem(model: AppModel, screen: Screen, icon: ImageVector, count: String? = null, badge: String? = null) {
    val c = colors
    val active = model.screen == screen
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) c.navActive else Color.Transparent)
            .clickable(role = Role.Tab) { model.screen = screen }
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics { selected = active }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val fg = if (active) Color.White else c.navText
        Icon(icon, null, Modifier.size(18.dp), tint = fg)
        Text(
            screen.label,
            Modifier.weight(1f),
            style = Type.body.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
            color = fg,
        )
        if (count != null) Text(count, style = Type.caption, color = c.navMuted)
        if (badge != null) {
            Text(
                badge,
                Modifier
                    .clip(CircleShape)
                    .background(c.accent)
                    .padding(horizontal = 8.dp, vertical = 1.dp)
                    .semantics { contentDescription = "$badge need attention" },
                style = Type.caption.copy(fontWeight = FontWeight.Bold),
                color = c.onAccent,
            )
        }
    }
}

/** Results of actions, newest at the bottom. Errors stay until closed, the rest go away after a while. */
@Composable
private fun Messages(model: AppModel, modifier: Modifier) {
    Column(modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (message in model.messages.takeLast(4)) {
            key(message.id) { MessageCard(model, message) }
        }
    }
}

@Composable
private fun MessageCard(model: AppModel, message: Message) {
    val c = colors
    if (!message.error) {
        LaunchedEffect(message.id) {
            delay(7_000)
            model.dismiss(message)
        }
    }
    val tone = if (message.error) c.error else c.neutral
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface)
            .border(1.dp, if (message.error) c.dangerBorder else c.border, RoundedCornerShape(10.dp))
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (message.error) Icons.warning else Icons.check,
            null,
            Modifier.padding(top = 1.dp).size(18.dp),
            tint = if (message.error) tone.fg else c.primary,
        )
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message.text, style = Type.body, color = c.text)
            if (message.error && model.logFile != null) {
                Text("Details are in ${model.logFile}", style = Type.caption, color = c.textMuted)
            }
        }
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(role = Role.Button) { model.dismiss(message) }
                .pointerHoverIcon(PointerIcon.Hand)
                .semantics { contentDescription = "Close" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.close, null, Modifier.size(16.dp), tint = c.textMuted)
        }
    }
}

// ---- Tones for model values ----

@Composable
fun ModLocation.tone(): Tone = when (this) {
    ModLocation.LOCAL -> colors.local
    ModLocation.STAGING -> colors.staging
    ModLocation.MOD_IO -> colors.modIo
}

@Composable
fun StatusKind.tone(): Tone = when (this) {
    StatusKind.OK -> colors.neutral
    StatusKind.UPDATE -> colors.info
    StatusKind.WARN -> colors.warn
    StatusKind.ERROR -> colors.error
}

@Composable
fun FeedSource.tone(): Tone = when (this) {
    FeedSource.TFNET -> colors.tfnet
    FeedSource.MODWERKSTATT -> colors.modwerkstatt
}
