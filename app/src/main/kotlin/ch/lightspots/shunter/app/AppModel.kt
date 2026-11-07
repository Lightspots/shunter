package ch.lightspots.shunter.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.lightspots.shunter.core.AvailableUpdate
import ch.lightspots.shunter.core.ModManager
import ch.lightspots.shunter.core.ModwerkstattLogoutResult
import ch.lightspots.shunter.core.feed.FeedResult
import ch.lightspots.shunter.core.feed.FeedSignInException
import ch.lightspots.shunter.core.feed.FeedSource
import ch.lightspots.shunter.core.feed.RemoteFile
import ch.lightspots.shunter.core.feed.RemoteMod
import ch.lightspots.shunter.core.install.InstalledFolder
import ch.lightspots.shunter.core.mod.InstalledMod
import ch.lightspots.shunter.core.mod.ModLocation
import ch.lightspots.shunter.core.mod.ModScanner
import ch.lightspots.shunter.core.modio.ModIoAuthException
import ch.lightspots.shunter.core.modio.ModIoLogin
import ch.lightspots.shunter.core.modio.ModIoMod
import ch.lightspots.shunter.core.modio.ModIoSubscriptions
import ch.lightspots.shunter.core.modwerkstatt.ModwerkstattAuthException
import ch.lightspots.shunter.core.modwerkstatt.ModwerkstattLogin
import ch.lightspots.shunter.core.net.DownloadProgress
import ch.lightspots.shunter.core.readableMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.name

private val logger = KotlinLogging.logger {}

enum class Screen(val label: String) { LIBRARY("Library"), BROWSE("Browse"), UPDATES("Updates"), SETTINGS("Settings") }

enum class Phase { WAITING, DOWNLOADING, INSTALLING, WORKING }

/** Something running for one mod: a download and install, a subscription change. Its state is observable. */
class Operation(val key: String, val title: String) {
    var phase by mutableStateOf(Phase.WAITING)
        internal set
    var downloaded by mutableLongStateOf(0L)
        internal set
    var total by mutableStateOf<Long?>(null)
        internal set
    internal var job: Job? = null

    fun cancel() {
        job?.cancel()
    }
}

data class Message(val id: Long, val text: String, val error: Boolean)

/** Which site the Browse screen shows. */
enum class BrowseSource(val label: String) {
    ALL("All"),
    TFNET("transportfever.net"),
    MODWERKSTATT("modwerkstatt.com"),
    MOD_IO("mod.io"),
    ;

    fun includes(source: FeedSource) = this == ALL || feed == source

    val feed: FeedSource?
        get() = when (this) {
            TFNET -> FeedSource.TFNET
            MODWERKSTATT -> FeedSource.MODWERKSTATT
            else -> null
        }

    val includesModIo: Boolean get() = this == ALL || this == MOD_IO
}

enum class BrowseSort(val label: String) { UPDATED("Recently updated"), NAME("Name") }

/**
 * The app's state and actions, on top of [ModManager]. State is Compose state, changed on the UI thread;
 * blocking work runs on [Dispatchers.IO].
 */
class AppModel(
    val manager: ModManager,
    val language: String,
    val logFile: Path?,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
) {
    var screen by mutableStateOf(Screen.LIBRARY)
    var theme by mutableStateOf(settings.theme)
        private set

    var library by mutableStateOf(LibraryData())
        private set
    var scanning by mutableStateOf(false)
        private set
    var feeds by mutableStateOf<Map<FeedSource, FeedResult>>(emptyMap())
        private set
    var feedIssues by mutableStateOf<Map<FeedSource, FeedIssue>>(emptyMap())
        private set
    var feedsLoading by mutableStateOf(false)
        private set
    var modIoIssue by mutableStateOf<String?>(null)
        private set

    /** Running operations by key: the feed reference for installs, `modio:<id>` for subscriptions. */
    val operations = mutableStateMapOf<String, Operation>()
    val messages = mutableStateListOf<Message>()
    private var nextMessageId = 0L

    var modIoLogin by mutableStateOf<ModIoLogin?>(null)
        private set
    var modwerkstattLogin by mutableStateOf<ModwerkstattLogin?>(null)
        private set

    /** The address a modwerkstatt.com code was mailed to; the Settings card then asks for the code. */
    var modwerkstattCodeSentTo by mutableStateOf<String?>(null)

    // Library screen
    var libraryFilter by mutableStateOf<ModLocation?>(null)
    var libraryQuery by mutableStateOf("")
    var selectedFolder by mutableStateOf<Path?>(null)

    // Browse screen
    var browseQuery by mutableStateOf("")
    var browseSource by mutableStateOf(BrowseSource.ALL)
    var browseSort by mutableStateOf(BrowseSort.UPDATED)
    var modIoResults by mutableStateOf<List<ModIoMod>>(emptyList())
        private set
    var modIoResultsTotal by mutableStateOf(0)
        private set
    var modIoSearching by mutableStateOf(false)
        private set
    var modIoSearchError by mutableStateOf<String?>(null)
        private set

    /** Installs change the mods folder and the install records; one at a time. */
    private val installLock = Mutex()
    private var libraryJob: Job? = null

    val canInstall: Boolean get() = manager.gamePaths.localMods != null

    val feedsFetchedAt: Instant? get() = feeds.values.minOfOrNull { it.fetchedAt }

    val problems: List<Problem> get() = library.problems(feedIssues, modIoIssue, language)

    /** Updates plus problems that need the user, for the badge in the sidebar. */
    val attentionCount: Int get() = library.updates.size + problems.count { it.severity != Severity.INFO }

    fun start() {
        scope.launch {
            val (modIo, modwerkstatt) = withContext(Dispatchers.IO) { manager.modIoLogins.load() to manager.modwerkstattLogin() }
            modIoLogin = modIo
            modwerkstattLogin = modwerkstatt
            refreshLibrary()
            launch { loadFeeds(force = false) }
            launch { loadModIoSubscriptions() }
            launch { checkModwerkstattAccount() }
            launch(Dispatchers.IO) { manager.images.prune() }
        }
    }

    fun updateTheme(choice: ThemeChoice) {
        theme = choice
        scope.launch(Dispatchers.IO) { settings.updateTheme(choice) }
    }

    // ---- Library ----

    fun rescan() {
        Previews.clear()
        scope.launch {
            refreshLibrary()
            loadModIoSubscriptions()
        }
    }

    private suspend fun refreshLibrary() {
        libraryJob?.cancel()
        val job = scope.launch {
            scanning = true
            try {
                val feedList = feeds.values.toList()
                val data = withContext(Dispatchers.IO) { scanLibrary(feedList) }
                library = data.copy(modIo = library.modIo?.let { ModIoSubscriptions(it.subscribed, data.modIoMods()) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Could not scan the mod folders" }
                showError("Could not scan the mod folders: ${e.readableMessage()}")
            } finally {
                scanning = false
            }
        }
        libraryJob = job
        job.join()
    }

    private fun scanLibrary(feeds: List<FeedResult>): LibraryData {
        val mods = manager.scan()
        val local = manager.gamePaths.localMods
        val records = local?.let { manager.registry.records(it) }.orEmpty()
        return LibraryData(
            mods = mods,
            records = records,
            updates = if (local != null && feeds.isNotEmpty()) manager.availableUpdates(feeds) else emptyList(),
            changedOutside = if (local != null) manager.changedOutside() else emptyList(),
            missing = manager.missingDependencies(mods, mods),
            duplicates = manager.duplicateModIds(mods),
        )
    }

    private fun LibraryData.modIoMods() = mods.filter { it.location == ModLocation.MOD_IO }

    fun remove(mod: InstalledMod) {
        val name = library.displayName(mod, language)
        scope.launch {
            try {
                val backup = installLock.withLock { withContext(Dispatchers.IO) { manager.uninstall(mod.folderName) } }
                showInfo("Removed $name. It was moved to the backups (${backup.name}).")
                if (selectedFolder == mod.folder) selectedFolder = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Could not remove ${mod.folderName}" }
                showError("Could not remove $name: ${e.readableMessage()}")
            }
            refreshLibrary()
        }
    }

    fun installArchive(archive: Path) {
        val key = "archive:$archive"
        if (key in operations) return
        val op = Operation(key, archive.name)
        operations[key] = op
        op.job = scope.launch {
            try {
                val installed = installLock.withLock {
                    op.phase = Phase.INSTALLING
                    withContext(Dispatchers.IO) { manager.installArchive(archive) }
                }
                afterInstall(archive.name, installed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Could not install $archive" }
                showError("Could not install ${archive.name}: ${e.readableMessage()}")
            } finally {
                operations.remove(key)
            }
        }
    }

    // ---- Feeds, installs and updates ----

    fun refreshFeeds() {
        scope.launch {
            loadFeeds(force = true)
            loadModIoSubscriptions()
        }
    }

    private suspend fun loadFeeds(force: Boolean, sources: Collection<FeedSource> = FeedSource.entries) {
        feedsLoading = true
        val maxAge = if (force) Duration.ZERO else Duration.ofHours(1)
        try {
            val results = coroutineScope {
                sources.map { source -> async { source to loadFeed(source, maxAge) } }.awaitAll()
            }
            val newFeeds = feeds.toMutableMap()
            val newIssues = feedIssues.toMutableMap()
            for ((source, outcome) in results) {
                outcome.result?.let { newFeeds[source] = it }
                if (outcome.issue != null) newIssues[source] = outcome.issue else newIssues.remove(source)
            }
            feeds = newFeeds
            feedIssues = newIssues
        } finally {
            feedsLoading = false
        }
        refreshLibrary()
    }

    private class FeedOutcome(val result: FeedResult?, val issue: FeedIssue?)

    private suspend fun loadFeed(source: FeedSource, maxAge: Duration): FeedOutcome = try {
        val result = manager.feeds.load(source, maxAge)
        FeedOutcome(
            result,
            result.error?.let {
                FeedIssue("Showing the copy from ${date(result.fetchedAt.epochSecond)}: $it", result.signInRefused)
            },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: FeedSignInException) {
        logger.warn(e) { "Could not load ${source.label}" }
        FeedOutcome(null, FeedIssue(e.message ?: "Sign-in refused", signInRefused = true))
    } catch (e: Exception) {
        logger.warn(e) { "Could not load ${source.label}" }
        FeedOutcome(null, FeedIssue(e.readableMessage()))
    }

    fun findRemote(ref: String): RemoteMod? {
        val source = FeedSource.byId(ref.substringBefore(':')) ?: return null
        return feeds[source]?.mods?.firstOrNull { it.id == ref.substringAfter(':') }
    }

    fun install(remote: RemoteMod, files: List<RemoteFile> = remote.files) {
        val key = remote.ref
        if (key in operations) return
        if (files.isEmpty()) {
            showError("${remote.name} has no file to download on ${remote.source.label} yet.")
            return
        }
        val op = Operation(key, remote.name)
        operations[key] = op
        op.job = scope.launch {
            try {
                val installed = installLock.withLock {
                    files.flatMap { file ->
                        op.phase = Phase.DOWNLOADING
                        op.downloaded = 0
                        op.total = file.size
                        manager.installRemoteFile(remote, file, progressOf(op))
                    }
                }
                afterInstall(remote.name, installed)
            } catch (e: CancellationException) {
                logger.info { "Cancelled installing ${remote.ref}" }
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Could not install ${remote.ref}" }
                showError("Could not install ${remote.name}: ${e.readableMessage()}")
            } finally {
                operations.remove(key)
            }
        }
    }

    fun update(update: AvailableUpdate) = install(update.remote, listOf(update.file))

    fun updateAll() = library.updates.forEach(::update)

    fun reinstall(ref: String) {
        val remote = findRemote(ref)
        if (remote == null) showError("$ref is not in the mod list of the site any more.") else install(remote)
    }

    private fun progressOf(op: Operation) = DownloadProgress { downloaded, total ->
        // Called on an IO thread; Compose state may be written from any thread
        op.downloaded = downloaded
        if (total != null) op.total = total
        val expected = total ?: op.total
        if (expected != null && downloaded >= expected) op.phase = Phase.INSTALLING
    }

    private suspend fun afterInstall(name: String, installed: List<InstalledFolder>) {
        val folders = installed.joinToString { it.folderName }
        val backup = if (installed.any { it.backup != null }) " The previous version is in the backups." else ""
        showInfo("Installed $name ($folders).$backup")
        refreshLibrary()
        val installedMods = withContext(Dispatchers.IO) { installed.map { ModScanner.read(it.target, ModLocation.LOCAL) } }
        val missing = manager.missingDependencies(installedMods, library.mods)
        if (missing.isNotEmpty()) {
            showError(missing.joinToString(" ") { "${library.displayName(it.mod, language)}: ${LibraryData.missingText(it)}" })
        }
    }

    // ---- mod.io ----

    private suspend fun loadModIoSubscriptions() {
        if (modIoLogin == null) {
            library = library.copy(modIo = null)
            modIoIssue = null
            return
        }
        try {
            val subscriptions = manager.modIoSubscriptions(language, library.modIoMods())
                ?: return
            library = library.copy(modIo = ModIoSubscriptions(subscriptions.subscribed, library.modIoMods()))
            modIoIssue = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModIoAuthException) {
            logger.warn(e) { "Could not load mod.io subscriptions" }
            modIoIssue = "${e.message} Sign in to mod.io again in Settings."
        } catch (e: Exception) {
            logger.warn(e) { "Could not load mod.io subscriptions" }
            modIoIssue = e.readableMessage()
        }
    }

    /** Searches mod.io for the Browse screen; empty results when not signed in. */
    suspend fun searchModIo(query: String) {
        val client = manager.modIo(language)
        if (client == null) {
            modIoResults = emptyList()
            modIoResultsTotal = 0
            return
        }
        modIoSearching = true
        try {
            val page = client.searchMods(query.ifBlank { null }, limit = 100)
            modIoResults = page.mods
            modIoResultsTotal = page.total
            modIoSearchError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "mod.io search failed" }
            modIoSearchError = if (e is ModIoAuthException) "${e.message} Sign in again in Settings." else e.readableMessage()
        } finally {
            modIoSearching = false
        }
    }

    fun subscribe(mod: ModIoMod) = changeSubscription(mod.id, mod.name, subscribe = true)

    fun unsubscribe(modIoId: Long, name: String) = changeSubscription(modIoId, name, subscribe = false)

    private fun changeSubscription(modIoId: Long, name: String, subscribe: Boolean) {
        val key = "modio:$modIoId"
        if (key in operations) return
        val client = manager.modIo(language) ?: run {
            showError("Sign in to mod.io in Settings first.")
            return
        }
        val op = Operation(key, name).apply { phase = Phase.WORKING }
        operations[key] = op
        op.job = scope.launch {
            try {
                if (subscribe) {
                    val result = client.subscribe(modIoId)
                    showInfo(
                        if (result.alreadySubscribed) {
                            "You were already subscribed to $name."
                        } else {
                            "Subscribed to $name. The game downloads it on its next start."
                        },
                    )
                } else {
                    val was = client.unsubscribe(modIoId)
                    showInfo(
                        if (was) "Unsubscribed from $name. The game removes it on its next start." else "You were not subscribed to $name.",
                    )
                }
                loadModIoSubscriptions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Could not change the subscription to $modIoId" }
                showError("Could not ${if (subscribe) "subscribe to" else "unsubscribe from"} $name: ${e.readableMessage()}")
            } finally {
                operations.remove(key)
            }
        }
    }

    /** Returns an error message, null on success. */
    suspend fun modIoSignIn(token: String): String? = try {
        val user = manager.modIoLogin(token)
        modIoLogin = withContext(Dispatchers.IO) { manager.modIoLogins.load() }
        showInfo("Signed in to mod.io as ${user.username}.")
        loadModIoSubscriptions()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(e) { "mod.io sign-in failed" }
        e.readableMessage()
    }

    fun modIoSignOut() {
        scope.launch {
            withContext(Dispatchers.IO) { manager.modIoLogout() }
            modIoLogin = null
            modIoResults = emptyList()
            loadModIoSubscriptions()
            showInfo("Signed out of mod.io. The token is deleted on this computer.")
        }
    }

    // ---- modwerkstatt.com ----

    private suspend fun checkModwerkstattAccount() {
        if (modwerkstattLogin == null) return
        try {
            modwerkstattLogin = manager.modwerkstattAccount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModwerkstattAuthException) {
            logger.info { "modwerkstatt.com sign-in no longer valid: ${e.message}" }
            modwerkstattLogin = null
            showError("${e.message} Sign in again in Settings to see mods that are not public yet.")
            loadFeeds(force = true, listOf(FeedSource.MODWERKSTATT))
        } catch (e: Exception) {
            // Offline or the site is down; the stored sign-in stays
            logger.warn(e) { "Could not check the modwerkstatt.com account" }
        }
    }

    suspend fun modwerkstattRequestCode(email: String): String? = try {
        manager.modwerkstattRequestCode(email.trim())
        modwerkstattCodeSentTo = email.trim()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(e) { "modwerkstatt.com code request failed" }
        e.readableMessage()
    }

    suspend fun modwerkstattConfirm(code: String): String? {
        val email = modwerkstattCodeSentTo ?: return "Ask for a code first."
        return try {
            val login = manager.modwerkstattConfirm(email, code.trim())
            modwerkstattLogin = login
            modwerkstattCodeSentTo = null
            showInfo("Signed in to modwerkstatt.com${login.name?.let { " as $it" }.orEmpty()}.")
            scope.launch { loadFeeds(force = true, listOf(FeedSource.MODWERKSTATT)) }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "modwerkstatt.com sign-in failed" }
            e.readableMessage()
        }
    }

    fun modwerkstattSignOut() {
        scope.launch {
            val result = try {
                manager.modwerkstattLogout()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "modwerkstatt.com sign-out failed" }
                showError("Could not sign out of modwerkstatt.com: ${e.readableMessage()}")
                return@launch
            }
            modwerkstattLogin = null
            showInfo(
                when (result) {
                    ModwerkstattLogoutResult.FORGOTTEN_LOCALLY ->
                        "Signed out on this computer. modwerkstatt.com could not be reached, so sign out on the website to end the session there."

                    else -> "Signed out of modwerkstatt.com."
                },
            )
            loadFeeds(force = true, listOf(FeedSource.MODWERKSTATT))
        }
    }

    // ---- Messages ----

    fun showInfo(text: String) {
        messages += Message(nextMessageId++, text, error = false)
    }

    fun showError(text: String) {
        messages += Message(nextMessageId++, text, error = true)
    }

    fun dismiss(message: Message) {
        messages.remove(message)
    }

    /** Opens the Browse screen searching for [query]. */
    fun find(query: String) {
        browseQuery = query
        browseSource = BrowseSource.ALL
        screen = Screen.BROWSE
    }

    fun openFolder(path: Path) {
        if (!Launcher.openFolder(path)) showError("Could not open $path. Is xdg-open installed?")
    }

    fun openUrl(url: String) {
        if (!Launcher.openUrl(url)) showError("Could not open $url.")
    }
}
