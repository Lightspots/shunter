package ch.lightspots.shunter.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ch.lightspots.shunter.app.AppModel
import ch.lightspots.shunter.app.ThemeChoice
import ch.lightspots.shunter.app.date
import ch.lightspots.shunter.core.modio.ModIoClient
import kotlinx.coroutines.launch
import java.nio.file.Path
import kotlin.io.path.exists

@Composable
fun SettingsScreen(model: AppModel) {
    val c = colors
    ScrollPage {
        ScreenHeader("Settings", "Accounts on the mod sites, and where Shunter reads and writes.")

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Accounts")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ModIoCard(model)
                ModwerkstattCard(model)
                AccountCard(c.tfnet, "transportfever.net", null) {
                    Text(
                        "No account needed. Downloads are checked against the size and SHA-256 the site publishes.",
                        style = Type.body,
                        color = c.textSecondary,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Appearance")
            Segmented(ThemeChoice.entries, model.theme, { it.label }, model::updateTheme)
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Folders")
            val paths = model.manager.gamePaths
            val dirs = model.manager.appDirs
            Panel {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("WHAT", Modifier.width(150.dp), style = Type.columnHeader, color = c.textMuted)
                    Text("FOLDER", Modifier.weight(1f), style = Type.columnHeader, color = c.textMuted)
                    Text("SHUNTER", Modifier.width(120.dp), style = Type.columnHeader, color = c.textMuted)
                    Text("", Modifier.width(72.dp))
                }
                Divider()
                FolderRow(model, "Installed mods", paths.localMods, "reads and writes")
                FolderRow(model, "Staging area", paths.stagingArea, "reads only")
                FolderRow(model, "Mod Hub", paths.modIoMods, "reads only")
                FolderRow(model, "Backups, records", dirs.data, "its own")
                FolderRow(model, "Downloads, lists", dirs.cache, "its own")
                FolderRow(model, "Sign-ins, settings", dirs.config, "its own")
                FolderRow(model, "Logs", dirs.logs, "its own", last = true)
            }
            Text(
                "Attach the log${model.logFile?.let { " (${it.fileName})" }.orEmpty()} to bug reports. " +
                    "Tokens and sign-ins are stored readable only by you.",
                style = Type.caption,
                color = c.textMuted,
            )
        }
    }
}

@Composable
private fun FolderRow(model: AppModel, what: String, path: Path?, access: String, last: Boolean = false) {
    val c = colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(what, Modifier.width(150.dp), style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        Text(path?.toString() ?: "not found", Modifier.weight(1f), style = Type.mono, color = c.textSecondary)
        Text(access, Modifier.width(120.dp), style = Type.small, color = c.textMuted)
        AppButton(
            "Open",
            { path?.let(model::openFolder) },
            Modifier.width(72.dp),
            height = 36.dp,
            enabled = path?.exists() == true,
        )
    }
    if (!last) Divider()
}

@Composable
private fun AccountCard(tone: Tone, site: String, title: String?, content: @Composable ColumnScope.() -> Unit) {
    Panel(Modifier.width(330.dp), padding = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Badge(site, tone)
                if (title != null) Text(title, style = Type.item, color = colors.heading)
            }
            content()
        }
    }
}

@Composable
private fun ModIoCard(model: AppModel) {
    val c = colors
    val scope = rememberCoroutineScope()
    var token by rememberState("")
    var busy by rememberState(false)
    var error by rememberState<String?>(null)
    AccountCard(c.modIo, "mod.io", "Mod Hub") {
        val login = model.modIoLogin
        if (login != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Signed in${login.username?.let {
                        " as $it"
                    }.orEmpty()}",
                    style = Type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = c.text,
                )
                val count = model.library.modIo?.subscribed?.size
                Text(
                    (count?.let { "$it subscriptions. " } ?: "") + "The game downloads new ones on its next start.",
                    style = Type.small,
                    color = c.textMuted,
                )
            }
            AppButton("Sign out", model::modIoSignOut)
            Text(
                "Signing out deletes the token on this computer. Revoke it on mod.io to make it invalid.",
                style = Type.caption,
                color = c.textMuted,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Text("1. ", style = Type.body, color = c.textSecondary)
                    Column {
                        Text("Create a personal access token with read and write access on", style = Type.body, color = c.textSecondary)
                        Link("mod.io/me/access", { model.openUrl(ModIoClient.ACCESS_PAGE) })
                        Text("You can sign in there with Steam.", style = Type.body, color = c.textSecondary)
                    }
                }
                Text("2. Paste it here.", style = Type.body, color = c.textSecondary)
            }
            val submit: () -> Unit = {
                if (token.isNotBlank() && !busy) {
                    busy = true
                    scope.launch {
                        error = model.modIoSignIn(token)
                        if (error == null) token = ""
                        busy = false
                    }
                }
            }
            LabeledInput("Access token", token, { token = it }, "Paste the token", password = true, onSubmit = submit, enabled = !busy)
            error?.let { Text(it, style = Type.small, color = c.danger) }
            AppButton(if (busy) "Signing in…" else "Sign in", submit, style = ButtonStyle.PRIMARY, enabled = token.isNotBlank() && !busy)
        }
    }
}

@Composable
private fun ModwerkstattCard(model: AppModel) {
    val c = colors
    val scope = rememberCoroutineScope()
    var email by rememberState("")
    var code by rememberState("")
    var busy by rememberState(false)
    var error by rememberState<String?>(null)
    AccountCard(c.modwerkstatt, "modwerkstatt.com", "Optional") {
        val login = model.modwerkstattLogin
        val sentTo = model.modwerkstattCodeSentTo
        when {
            login != null -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Signed in as ${login.name ?: login.email}",
                        style = Type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = c.text,
                    )
                    Text("Session valid until ${date(login.expiresAt)}.", style = Type.small, color = c.textMuted)
                    login.membershipUntil?.let { Text("Membership until ${it.take(10)}.", style = Type.small, color = c.textMuted) }
                }
                AppButton("Sign out", model::modwerkstattSignOut)
            }

            sentTo != null -> {
                Text(
                    buildAnnotatedString {
                        append("modwerkstatt.com mailed a code to ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(sentTo) }
                        append(".")
                    },
                    style = Type.body,
                    color = c.textSecondary,
                )
                val submit: () -> Unit = {
                    if (code.isNotBlank() && !busy) {
                        busy = true
                        scope.launch {
                            error = model.modwerkstattConfirm(code)
                            if (error == null) code = ""
                            busy = false
                        }
                    }
                }
                LabeledInput(
                    "Code",
                    code,
                    { code = it },
                    "Code from the email",
                    textStyle = Type.mono.copy(fontSize = 16.sp, letterSpacing = 0.1.em),
                    onSubmit = submit,
                    enabled = !busy,
                )
                error?.let { Text(it, style = Type.small, color = c.danger) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(
                        if (busy) "Signing in…" else "Sign in",
                        submit,
                        style = ButtonStyle.PRIMARY,
                        enabled =
                        code.isNotBlank() && !busy,
                    )
                    AppButton("Other address", {
                        model.modwerkstattCodeSentTo = null
                        error = null
                    })
                }
            }

            else -> {
                Text(
                    "Downloads work without an account. Signed in, you also see mods that are not public yet.",
                    style = Type.body,
                    color = c.textSecondary,
                )
                val submit: () -> Unit = {
                    if (email.isNotBlank() && !busy) {
                        busy = true
                        scope.launch {
                            error = model.modwerkstattRequestCode(email)
                            busy = false
                        }
                    }
                }
                LabeledInput("Email address of your account", email, { email = it }, "you@example.com", onSubmit = submit, enabled = !busy)
                error?.let { Text(it, style = Type.small, color = c.danger) }
                AppButton(if (busy) "Sending…" else "Send code", submit, style = ButtonStyle.PRIMARY, enabled = email.isNotBlank() && !busy)
            }
        }
    }
}
