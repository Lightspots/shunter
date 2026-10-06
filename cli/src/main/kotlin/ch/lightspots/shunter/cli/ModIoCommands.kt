package ch.lightspots.shunter.cli

import ch.lightspots.shunter.core.mod.ModLocation
import ch.lightspots.shunter.core.modio.ModIoAuthException
import ch.lightspots.shunter.core.modio.ModIoClient
import ch.lightspots.shunter.core.modio.ModIoMod
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import kotlin.io.path.isDirectory

/** Hint shown wherever mod.io needs a sign-in. */
internal const val MOD_IO_LOGIN_HINT = "Sign in with: shunter modio login"

/** Hint for a token mod.io no longer accepts, or one without write access. */
internal const val MOD_IO_RELOGIN_HINT =
    "Create a new token with read and write access on ${ModIoClient.ACCESS_PAGE} and sign in again with: shunter modio login"

class ModIoCommand : CliktCommand("modio") {
    override fun help(context: Context) =
        "mod.io, the game's Mod Hub: sign in, browse and subscribe to mods. The game downloads subscribed mods itself."

    init {
        subcommands(ModIoLoginCommand(), ModIoLogoutCommand(), ModIoSearchCommand(), ModIoSubscribeCommand(), ModIoUnsubscribeCommand())
    }

    override fun run() = Unit
}

/** Base for commands that need a signed-in mod.io account. */
abstract class ModIoClientCommand(name: String) : ManagerCommand(name) {
    protected fun client(): ModIoClient = manager.modIo(language) ?: throw CliktError("Not signed in to mod.io. $MOD_IO_LOGIN_HINT")

    /** Runs [block], turning a refused token into a hint how to sign in again. */
    protected suspend fun <T> withModIo(block: suspend () -> T): T = try {
        block()
    } catch (e: ModIoAuthException) {
        throw CliktError("${e.message}\n$MOD_IO_RELOGIN_HINT", cause = e)
    }
}

class ModIoLoginCommand : ModIoClientCommand("login") {
    override fun help(context: Context) =
        "Sign in to mod.io with a personal access token. The token is read from the terminal (or standard input), never from the command line."

    override suspend fun execute() {
        val interactive = terminal.terminalInfo.inputInteractive
        if (interactive) {
            echo("Create a personal access token on ${highlight(ModIoClient.ACCESS_PAGE)}")
            echo(muted("(sign in there with Steam or email; give it read and write access) and paste it here."))
            echo("Access token: ", trailingNewline = false)
        }
        // Hidden input on a terminal; piped input (shunter modio login < file) is read as it is
        val token = terminal.readLineOrNull(hideInput = true)?.trim()
        if (interactive) echo()
        if (token.isNullOrEmpty()) throw CliktError("No access token entered.")

        val user = withModIo { manager.modIoLogin(token) }
        echo("${success("Signed in to mod.io")} as ${bold(user.username)}")
        echo(muted("The token is stored in ${manager.modIoLogins.file}, readable only by you."))
    }
}

class ModIoLogoutCommand : ModIoClientCommand("logout") {
    override fun help(context: Context) = "Forget the stored mod.io access token."

    override suspend fun execute() {
        if (!manager.modIoLogout()) {
            echo("Not signed in to mod.io.")
            return
        }
        echo(
            "${success(
                "Signed out of mod.io.",
            )} ${muted("The token itself stays valid until you revoke it on ${ModIoClient.ACCESS_PAGE}.")}",
        )
    }
}

class ModIoSearchCommand : ModIoClientCommand("search") {
    override fun help(context: Context) = "Search Transport Fever 3 mods on mod.io, most recently updated first."

    private val query by argument(help = "Search text").optional()
    private val limit by option("--limit", "-n", help = "Show at most this many mods (1-100)").int().restrictTo(1, 100).default(50)

    override suspend fun execute() {
        val page = withModIo { client().searchMods(query, limit) }
        if (page.mods.isEmpty()) {
            echo("Nothing found.")
            return
        }
        val inFolder = manager.scan(listOf(ModLocation.MOD_IO)).map { it.folderName }.toSet()
        echo(
            table(
                listOf("", "ID", "VERSION", "UPDATED", "SIZE", "SUBSCRIBERS", "NAME", "AUTHOR"),
                page.mods.map { m ->
                    listOf(
                        if (m.folderName in inFolder) success("*") else "",
                        m.id.toString(),
                        m.version ?: "",
                        date(m.updatedAt),
                        humanSize(m.fileSize),
                        m.subscribers?.toString() ?: "",
                        m.name,
                        m.author ?: "",
                    )
                },
            ),
        )
        if (page.total > page.mods.size) echo(muted("${page.mods.size} of ${page.total} shown"))
        if (page.mods.any { it.folderName in inFolder }) echo(muted("* = in the mod.io folder"))
    }
}

class ModIoSubscribeCommand : ModIoClientCommand("subscribe") {
    override fun help(context: Context) =
        "Subscribe to a mod on mod.io, and to the mods it depends on. The game downloads them when it starts."

    private val mod by argument(help = "mod.io id, name id, or address of the mod")
    private val yes by option("--yes", "-y", help = "Do not ask for confirmation").flag()

    override suspend fun execute() = withModIo<Unit> {
        val client = client()
        val target = client.findMod(mod) ?: throw CliktError("mod.io has no Transport Fever 3 mod '$mod'")
        val dependencies = client.dependencies(target.id)

        echo("${bold(target.name)}${target.version?.let { " $it" } ?: ""}${target.author?.let { muted(" by $it") } ?: ""}")
        target.profileUrl?.let { echo(muted("  $it")) }
        dependencies.forEach { echo("  requires: ${it.name} ${muted("(mod.io ${it.id})")}") }
        warnAboutLocalCopies(listOf(target) + dependencies)

        val what = if (dependencies.isEmpty()) target.name else "${target.name} and ${dependencies.size} required mod(s)"
        if (!confirm("Subscribe to $what?", yes)) return@withModIo

        val result = client.subscribe(target.id, includeDependencies = true)
        if (result.alreadySubscribed) {
            echo("Already subscribed to ${target.name}.")
        } else {
            echo("${success("Subscribed")} to $what.")
        }
        echo(muted("Transport Fever 3 downloads subscribed mods when it starts."))
    }

    /**
     * Mods already in the mod.io folder whose mod id is in local/mods too: the game would see it twice.
     * Mods not downloaded yet cannot be checked, their mod id is only known from their mod.json.
     */
    private fun warnAboutLocalCopies(mods: List<ModIoMod>) {
        val folders = mods.map { it.folderName }.toSet()
        val downloaded = manager.scan(listOf(ModLocation.MOD_IO)).filter { it.folderName in folders && it.modId != null }
        if (downloaded.isEmpty()) return
        val localById = manager.scan(listOf(ModLocation.LOCAL)).filter { it.modId != null }.groupBy { it.modId }
        for (m in downloaded) {
            localById[m.modId]?.forEach { local ->
                echo("${warning("Warning:")} mod id '${m.modId}' is also in local/${local.folderName}; the game would load it twice")
            }
        }
    }
}

class ModIoUnsubscribeCommand : ModIoClientCommand("unsubscribe") {
    override fun help(context: Context) =
        "Unsubscribe from a mod on mod.io. Mods it depends on stay subscribed; its folder is left to the game."

    private val mod by argument(help = "mod.io id, name id, or address of the mod")
    private val yes by option("--yes", "-y", help = "Do not ask for confirmation").flag()

    override suspend fun execute() = withModIo<Unit> {
        val client = client()
        val target = client.findMod(mod)
        // A mod that was removed from mod.io can still be unsubscribed by its id
        val id = target?.id ?: mod.trim().toLongOrNull() ?: throw CliktError("mod.io has no Transport Fever 3 mod '$mod'")
        val name = target?.name ?: "mod.io mod $id"
        if (!confirm("Unsubscribe from $name?", yes)) return@withModIo

        if (!client.unsubscribe(id)) {
            echo("You were not subscribed to $name.")
            return@withModIo
        }
        echo("${success("Unsubscribed")} from $name.")
        manager.gamePaths.modIoMods?.resolve(id.toString())?.takeIf { it.isDirectory() }?.let {
            echo(muted("Its folder $it belongs to the game; shunter leaves it alone."))
        }
    }
}
