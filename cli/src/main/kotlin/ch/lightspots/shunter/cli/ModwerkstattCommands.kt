package ch.lightspots.shunter.cli

import ch.lightspots.shunter.core.ModwerkstattLogoutResult
import ch.lightspots.shunter.core.modwerkstatt.ModwerkstattAuthException
import ch.lightspots.shunter.core.modwerkstatt.ModwerkstattLogin
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.option

/** Hint for a modwerkstatt.com sign-in that expired or was ended elsewhere. */
internal const val MODWERKSTATT_RELOGIN_HINT = "Sign in again with: shunter modwerkstatt login"

class ModwerkstattCommand : CliktCommand("modwerkstatt") {
    override fun help(context: Context) =
        "modwerkstatt.com: sign in with your account. Signed in, its mod list and downloads use your account (alias: mw)."

    init {
        subcommands(ModwerkstattLoginCommand(), ModwerkstattLogoutCommand(), ModwerkstattStatusCommand())
    }

    override fun run() = Unit
}

class ModwerkstattLoginCommand : ManagerCommand("login") {
    override fun help(context: Context) =
        "Sign in to modwerkstatt.com. It mails you a code, which you enter here. Email and code are read from the terminal " +
            "(or standard input, one per line)."

    private val email by option("--email", help = "Email address of your modwerkstatt.com account")

    override suspend fun execute() {
        val interactive = terminal.terminalInfo.inputInteractive
        val address = email?.trim() ?: run {
            if (interactive) echo("Email address of your modwerkstatt.com account: ", trailingNewline = false)
            terminal.readLineOrNull(hideInput = false)?.trim()
        }
        if (address.isNullOrEmpty()) throw CliktError("No email address entered.")

        manager.modwerkstattRequestCode(address)
        if (interactive) echo("modwerkstatt.com has mailed a code to ${bold(address)}. Code: ", trailingNewline = false)
        val code = terminal.readLineOrNull(hideInput = false)?.trim()
        if (code.isNullOrEmpty()) throw CliktError("No code entered.")

        val login = manager.modwerkstattConfirm(address, code)
        echo("${success("Signed in to modwerkstatt.com")} as ${bold(login.name ?: login.email)}")
        echo(muted(membership(login)))
        echo(muted("The sign-in is stored in ${manager.modwerkstattLogins.file}, readable only by you."))
    }
}

class ModwerkstattLogoutCommand : ManagerCommand("logout") {
    override fun help(context: Context) = "Sign out of modwerkstatt.com and forget the stored sign-in."

    override suspend fun execute() {
        when (manager.modwerkstattLogout()) {
            ModwerkstattLogoutResult.NOT_SIGNED_IN -> echo("Not signed in to modwerkstatt.com.")

            ModwerkstattLogoutResult.SIGNED_OUT -> echo(success("Signed out of modwerkstatt.com."))

            ModwerkstattLogoutResult.FORGOTTEN_LOCALLY -> {
                echo(
                    "${warning("Warning:")} modwerkstatt.com did not confirm the sign-out, so the session may stay valid there.",
                    err = true,
                )
                echo("The sign-in is forgotten here. Sign out on modwerkstatt.com to end the session.")
            }
        }
    }
}

class ModwerkstattStatusCommand : ManagerCommand("status") {
    override fun help(context: Context) = "Show the modwerkstatt.com account shunter is signed in with."

    override suspend fun execute() {
        val login = try {
            manager.modwerkstattAccount()
        } catch (e: ModwerkstattAuthException) {
            throw CliktError("${e.message}\n$MODWERKSTATT_RELOGIN_HINT", cause = e)
        }
        if (login == null) {
            echo("Not signed in to modwerkstatt.com. Sign in with: shunter modwerkstatt login")
            return
        }
        echo("Signed in to modwerkstatt.com as ${bold(login.name ?: login.email)} ${muted("(${login.email})")}")
        echo(membership(login))
        echo(muted("Sign-in valid until ${date(login.expiresAt)}"))
    }
}

private fun membership(login: ModwerkstattLogin) =
    login.membershipUntil?.takeIf { it.isNotBlank() }?.let { "Membership until $it" } ?: "No active membership"
