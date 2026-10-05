package ch.lightspots.shunter.cli

import ch.lightspots.shunter.core.ChangedOutside
import ch.lightspots.shunter.core.ModManager
import ch.lightspots.shunter.core.feed.FeedResult
import ch.lightspots.shunter.core.feed.FeedService
import ch.lightspots.shunter.core.feed.FeedSource
import ch.lightspots.shunter.core.feed.RemoteMod
import ch.lightspots.shunter.core.install.InstalledFolder
import ch.lightspots.shunter.core.mod.InstalledMod
import ch.lightspots.shunter.core.mod.ModLocation
import ch.lightspots.shunter.core.mod.ModScanner
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

private val logger = KotlinLogging.logger {}

/** Base for commands that use the [ModManager]; turns core exceptions into clean error messages. */
abstract class ManagerCommand(name: String) : CliktCommand(name) {
    private val cli by requireObject<CliContext>()
    protected val manager: ModManager get() = cli.manager
    protected val language: String get() = cli.language
    protected val cliContext: CliContext get() = cli

    protected abstract suspend fun execute()

    final override fun run() {
        logger.info { "Running $commandName" }
        try {
            runBlocking { execute() }
        } catch (e: CliktError) {
            logger.info { "$commandName: ${e.message}" }
            throw e
        } catch (e: Exception) {
            logger.error(e) { "$commandName failed" }
            throw CliktError(errorMessage(e, cli.logFile), cause = e)
        }
    }

    protected fun confirm(question: String, assumeYes: Boolean): Boolean {
        if (assumeYes) return true
        print("$question [y/N] ")
        System.out.flush()
        return readlnOrNull()?.trim()?.lowercase() in setOf("y", "yes", "j", "ja")
    }

    protected suspend fun loadFeeds(sources: Collection<FeedSource>, refresh: Boolean): List<FeedResult> {
        val service = FeedService(manager.appDirs, cli.http)
        val maxAge = if (refresh) Duration.ZERO else Duration.ofHours(1)
        return sources.map { source ->
            service.load(source, maxAge).also { result ->
                result.error?.let {
                    echo("Warning: could not refresh ${source.label} ($it), using copy from ${result.fetchedAt}", err = true)
                }
            }
        }
    }

    /** Feed references (`tfnet:8126`) of mods installed with shunter. */
    protected fun installedRefs(): Set<String> = manager.registry.records(manager.modsDir).values
        .mapNotNull { it.origin.ref }
        .toSet()

    /** Prints problems the game would run into after installing or with the current mod set. */
    protected fun reportProblems(focus: List<InstalledMod>? = null, remote: RemoteMod? = null) {
        val all = manager.scan()
        val checked = focus ?: all
        checked.filter { it.problems.isNotEmpty() }.forEach { mod ->
            echo("Problem in ${mod.location.label}/${mod.folderName}: ${mod.problems.joinToString("; ")}")
        }
        val missing = manager.missingDependencies(checked, all)
        for (m in missing) {
            val name = m.displayName?.let { "$it (${m.dependency.modId})" } ?: m.dependency.modId
            val why = m.wrongRevision?.let { "installed revision $it is outside the required range" } ?: "not installed"
            echo("Warning: ${m.mod.displayName(language)} requires $name: $why")
        }
        if (missing.isNotEmpty() && remote != null) {
            remote.dependencies.filter { it.remoteId != null }.forEach {
                echo("  The download page lists: ${it.name ?: "?"}, install with: shunter install ${remote.source.id}:${it.remoteId}")
            }
        }
        val checkedIds = checked.mapNotNull { it.modId }.toSet()
        manager.duplicateModIds(all).filterKeys { it in checkedIds }.forEach { (modId, mods) ->
            echo("Warning: mod id '$modId' exists more than once: ${mods.joinToString { "${it.location.label}/${it.folderName}" }}")
        }
    }

    /** Mods replaced or removed by hand since shunter installed them; their updates are not checked. */
    protected fun printChangedOutside(changed: List<ChangedOutside>) {
        for (c in changed) {
            val record = c.record
            val current = c.current
            val what = when {
                current == null -> "folder was removed"
                current.modId != record.modId -> "now holds mod id ${current.modId ?: "?"}"
                else -> "revision ${current.manifest?.revision ?: "?"} instead of ${record.revision ?: "?"}"
            }
            val hint = record.origin.ref?.takeIf { current != null }?.let { ", reinstall with: shunter install $it" }.orEmpty()
            echo("${record.folderName}: changed outside shunter ($what), installed version unknown$hint")
        }
    }

    protected fun printInstalled(folders: List<InstalledFolder>) {
        for (f in folders) {
            echo("Installed ${f.folderName} (mod id ${f.modId}, revision ${f.revision ?: "?"})")
            f.backup?.let { echo("  previous version saved to $it") }
        }
    }
}

private fun parseRef(ref: String): Pair<FeedSource, String> {
    val (prefix, id) = if (':' in ref) ref.substringBefore(':') to ref.substringAfter(':') else "tfnet" to ref
    val source = when (prefix.lowercase()) {
        "mw" -> FeedSource.MODWERKSTATT

        else -> FeedSource.byId(prefix)
            ?: throw CliktError("Unknown source '$prefix', use one of: ${FeedSource.entries.joinToString { it.id }}")
    }
    if (id.isBlank()) throw CliktError("Missing id in '$ref'")
    return source to id
}

class PathsCommand : ManagerCommand("paths") {
    override fun help(context: Context) = "Show the folders shunter uses."

    override suspend fun execute() {
        val paths = manager.gamePaths
        fun show(label: String, path: Path?) =
            echo("${label.padEnd(16)} ${path ?: "(not found)"}${if (path != null && !path.exists()) "  (does not exist yet)" else ""}")

        echo("Game")
        show("  local mods", paths.localMods)
        show("  staging area", paths.stagingArea)
        show("  mod.io mods", paths.modIoMods ?: cliContext.detector.modIoModsDir())
        val candidates = cliContext.detector.steamUserCandidates()
        if (candidates.size > 1) {
            echo("  Steam accounts with TF3 data: ${candidates.joinToString { it.accountId }} (pick one with --steam-account)")
        }
        echo("shunter")
        show("  data", manager.appDirs.data)
        show("  cache", manager.appDirs.cache)
        show("  backups", manager.appDirs.backups)
        show("  log", cliContext.logFile ?: manager.appDirs.logs)
    }
}

class ListCommand : ManagerCommand("list") {
    override fun help(context: Context) = "List installed mods from all mod folders."

    private val locations by option("--location", "-l", help = "Only these locations")
        .choice(ModLocation.entries.associateBy { it.label })
        .multiple()

    override suspend fun execute() {
        val mods = manager.scan(locations.ifEmpty { ModLocation.entries })
        if (mods.isEmpty()) {
            echo("No mods found.")
            return
        }
        val records = manager.gamePaths.localMods?.let { manager.registry.records(it) }.orEmpty()
        echo(
            table(
                listOf("LOCATION", "FOLDER", "MOD ID", "REV", "SOURCE", "NAME"),
                mods.map { mod ->
                    val origin = records[mod.folderName]?.takeIf { mod.location == ModLocation.LOCAL }?.origin
                    listOf(
                        mod.location.label,
                        mod.folderName,
                        mod.modId ?: "?",
                        mod.manifest?.revision?.toString() ?: "?",
                        origin?.ref ?: "",
                        mod.displayName(language),
                    )
                },
            ),
        )
        reportProblems(mods)
    }
}

class SearchCommand : ManagerCommand("search") {
    override fun help(context: Context) = "List mods available on transportfever.net and modwerkstatt.com."

    private val query by argument(help = "Filter by name, author or tag").optional()
    private val source by option("--source", "-s").choice(FeedSource.entries.associateBy { it.id })
    private val refresh by option("--refresh", help = "Download the mod lists again even if the cached copy is recent").flag()

    override suspend fun execute() {
        val feeds = loadFeeds(listOfNotNull(source).ifEmpty { FeedSource.entries }, refresh)
        val q = query?.lowercase()
        val installed = runCatching { installedRefs() }.getOrDefault(emptySet())
        val mods = feeds.flatMap { it.mods }
            .filter { m ->
                q == null || listOfNotNull(m.name, m.author).plus(m.tags).any { it.lowercase().contains(q) }
            }
            .sortedByDescending { it.updatedAt ?: 0 }
        for (feed in feeds.filter { it.mods.isEmpty() }) {
            echo("${feed.source.label}: no Transport Fever 3 mods listed (yet).")
        }
        if (mods.isEmpty()) {
            echo("Nothing found.")
            return
        }
        echo(
            table(
                listOf("", "REF", "VERSION", "UPDATED", "SIZE", "NAME", "AUTHOR"),
                mods.map { m ->
                    listOf(
                        if (m.ref in installed) "*" else "",
                        m.ref,
                        m.version ?: "",
                        date(m.updatedAt),
                        humanSize(m.files.sumOf { it.size ?: 0 }.takeIf { size -> m.files.all { it.size != null } }),
                        m.name,
                        m.author ?: "",
                    )
                },
            ),
        )
        if (installed.isNotEmpty()) echo("* = installed with shunter")
    }
}

class InstallCommand : ManagerCommand("install") {
    override fun help(context: Context) =
        "Install a mod from a feed (tfnet:8126, mw:5204, or just 8126 for transportfever.net) or from a local .zip/.7z archive."

    private val target by argument(help = "Feed reference or archive path")
    private val yes by option("--yes", "-y", help = "Do not ask for confirmation").flag()
    private val refresh by option("--refresh", help = "Download the mod list again first").flag()

    override suspend fun execute() {
        val archive = Path(target)
        when {
            archive.isRegularFile() -> installArchive(archive)

            // Feed references never look like this, so a missing file is not reported as an unknown feed entry
            looksLikePath(target) -> throw CliktError(
                if (archive.exists()) "Not an archive file: $archive" else "Archive not found: $archive",
            )

            else -> installRemote()
        }
    }

    private fun looksLikePath(target: String) = '/' in target || ARCHIVE_EXTENSIONS.any { target.endsWith(it, ignoreCase = true) }

    private fun installArchive(archive: Path) {
        if (!confirm("Install $archive into ${manager.modsDir}?", yes)) return
        val installed = manager.installArchive(archive)
        printInstalled(installed)
        reportProblems(installed.map { ModScanner.read(it.target, ModLocation.LOCAL) })
    }

    private suspend fun installRemote() {
        val (source, id) = parseRef(target)
        val feeds = loadFeeds(listOf(source), refresh)
        val remote = ModManager.findRemote(feeds, source, id)
            ?: throw CliktError("${source.label} has no Transport Fever 3 entry $id (try --refresh)")

        echo("${remote.name}${remote.version?.let { " $it" } ?: ""}${remote.author?.let { " by $it" } ?: ""}")
        remote.pageUrl?.let { echo("  $it") }
        remote.files.forEach {
            echo("  file: ${it.fileName} (${humanSize(it.size)})${if (it.sha256 != null) ", checksum verified after download" else ""}")
        }
        if (remote.dependencies.isNotEmpty()) {
            val installed = installedRefs()
            remote.dependencies.forEach { dep ->
                val ref = dep.remoteId?.let { "${source.id}:$it" }
                val state = if (ref in installed) "installed" else "check: ${ref?.let { "shunter install $it" } ?: dep.pageUrl ?: "?"}"
                echo("  ${if (dep.required) "requires" else "optional"}: ${dep.name ?: "?"} ($state)")
            }
        }
        if (!confirm("Install into ${manager.modsDir}?", yes)) return

        val installed = manager.installRemote(remote) { file -> ConsoleProgress(file.fileName) }
        printInstalled(installed)
        reportProblems(installed.map { ModScanner.read(it.target, ModLocation.LOCAL) }, remote)
    }

    private companion object {
        val ARCHIVE_EXTENSIONS = listOf(".zip", ".7z", ".rar")
    }
}

class UpdatesCommand : ManagerCommand("updates") {
    override fun help(context: Context) = "Show updates for mods installed with shunter."

    private val refresh by option("--refresh", help = "Download the mod lists again first").flag()

    override suspend fun execute() {
        val updates = manager.availableUpdates(loadFeeds(FeedSource.entries, refresh))
        val changed = manager.changedOutside()
        if (updates.isEmpty()) {
            echo(if (changed.isEmpty()) "All mods installed with shunter are up to date." else "No updates found.")
            printChangedOutside(changed)
            return
        }
        echo(
            table(
                listOf("FOLDER", "INSTALLED", "AVAILABLE", "REF"),
                updates.map { u ->
                    listOf(
                        u.record.folderName,
                        u.record.origin.version ?: date(u.record.origin.remoteChangedAt),
                        u.remote.version ?: date(u.file.changedAt),
                        u.remote.ref,
                    )
                },
            ),
        )
        echo("Install with: shunter update --all  (or: shunter update <folder>...)")
        printChangedOutside(changed)
    }
}

class UpdateCommand : ManagerCommand("update") {
    override fun help(context: Context) = "Install available updates."

    private val folders by argument(help = "Mod folders to update").multiple()
    private val all by option("--all", help = "Update everything").flag()
    private val yes by option("--yes", "-y", help = "Do not ask for confirmation").flag()
    private val refresh by option("--refresh", help = "Download the mod lists again first").flag()

    override suspend fun execute() {
        if (folders.isEmpty() && !all) throw CliktError("Name the mod folders to update, or use --all")
        val updates = manager.availableUpdates(loadFeeds(FeedSource.entries, refresh))
            .filter { all || it.record.folderName in folders }
        val unknown = folders - updates.map { it.record.folderName }.toSet()
        val changed = manager.changedOutside().filter { it.record.folderName in unknown }
        printChangedOutside(changed)
        (unknown - changed.map { it.record.folderName }.toSet()).forEach { echo("No update for $it") }
        if (updates.isEmpty()) return

        updates.forEach { echo("${it.record.folderName}: ${it.file.fileName} (${humanSize(it.file.size)})") }
        if (!confirm("Install ${updates.size} update(s)?", yes)) return
        for (u in updates) {
            printInstalled(manager.installRemoteFile(u.remote, u.file, ConsoleProgress(u.file.fileName)))
        }
    }
}

class RemoveCommand : ManagerCommand("remove") {
    override fun help(context: Context) = "Remove a mod from the local mods folder. It is moved to the backups, not deleted."

    private val folder by argument(help = "Mod folder name in local/mods")
    private val yes by option("--yes", "-y", help = "Do not ask for confirmation").flag()

    override suspend fun execute() {
        val dir = manager.modsDir.resolve(folder)
        if (!dir.isDirectory()) throw CliktError("No mod folder '$folder' in ${manager.modsDir}")
        val mod = ModScanner.read(dir, ModLocation.LOCAL)
        mod.manifest?.severityRemove?.let { echo("Note: the mod declares removal severity $it for existing savegames.") }
        if (!confirm("Remove ${mod.displayName(language)} ($folder)?", yes)) return
        echo("Moved to ${manager.uninstall(folder)}")
    }
}
