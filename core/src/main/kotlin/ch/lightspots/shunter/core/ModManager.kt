package ch.lightspots.shunter.core

import ch.lightspots.shunter.core.feed.FeedResult
import ch.lightspots.shunter.core.feed.FeedService
import ch.lightspots.shunter.core.feed.FeedSource
import ch.lightspots.shunter.core.feed.RemoteFile
import ch.lightspots.shunter.core.feed.RemoteMod
import ch.lightspots.shunter.core.install.InstallOrigin
import ch.lightspots.shunter.core.install.InstallRecord
import ch.lightspots.shunter.core.install.InstallRegistry
import ch.lightspots.shunter.core.install.InstalledFolder
import ch.lightspots.shunter.core.install.ModInstaller
import ch.lightspots.shunter.core.mod.InstalledMod
import ch.lightspots.shunter.core.mod.ModLocation
import ch.lightspots.shunter.core.mod.ModReference
import ch.lightspots.shunter.core.mod.ModScanner
import ch.lightspots.shunter.core.modio.ModIoClient
import ch.lightspots.shunter.core.modio.ModIoLogin
import ch.lightspots.shunter.core.modio.ModIoLoginStore
import ch.lightspots.shunter.core.modio.ModIoSubscriptions
import ch.lightspots.shunter.core.modio.ModIoUser
import ch.lightspots.shunter.core.modwerkstatt.ModwerkstattAuthException
import ch.lightspots.shunter.core.modwerkstatt.ModwerkstattClient
import ch.lightspots.shunter.core.modwerkstatt.ModwerkstattLogin
import ch.lightspots.shunter.core.modwerkstatt.ModwerkstattLoginStore
import ch.lightspots.shunter.core.net.DownloadProgress
import ch.lightspots.shunter.core.net.HttpDownloader
import ch.lightspots.shunter.core.net.ImageCache
import ch.lightspots.shunter.core.paths.AppDirs
import ch.lightspots.shunter.core.paths.GamePaths
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.isDirectory

private val logger = KotlinLogging.logger {}

/** A mod we installed from a feed, with a newer file available there. */
data class AvailableUpdate(val record: InstallRecord, val remote: RemoteMod, val file: RemoteFile)

/**
 * A mod installed with shunter whose folder no longer matches the install record, because it was
 * replaced or removed by hand. Which version is installed is unknown, so no update is offered.
 */
data class ChangedOutside(
    val record: InstallRecord,
    /** What is in the folder now; null when the folder is gone. */
    val current: InstalledMod?,
)

/** A `mod.json` dependency that no installed mod satisfies. */
data class MissingDependency(
    val mod: InstalledMod,
    val dependency: ModReference,
    val displayName: String?,
    /** An installed mod with that id exists, but its revision is outside the allowed range. */
    val wrongRevision: Int? = null,
)

/** Outcome of [ModManager.modwerkstattLogout]. */
enum class ModwerkstattLogoutResult {
    NOT_SIGNED_IN,

    /** modwerkstatt.com ended the session (or it had expired), and the token is forgotten. */
    SIGNED_OUT,

    /**
     * The token is forgotten, but modwerkstatt.com could not be told, so the session may stay valid there
     * until it expires. Signing out on the website ends it.
     */
    FORGOTTEN_LOCALLY,
}

/**
 * Entry point for frontends (CLI, UI): everything they need, without knowing the parts.
 * Suspend functions move their blocking work to [Dispatchers.IO]; the others block the caller.
 */
class ModManager(
    val gamePaths: GamePaths,
    val appDirs: AppDirs = AppDirs.fromEnvironment(),
    private val http: HttpDownloader = HttpDownloader(),
    val registry: InstallRegistry = InstallRegistry(appDirs.registryFile),
    private val installer: ModInstaller = ModInstaller(appDirs, registry),
    val modIoLogins: ModIoLoginStore = ModIoLoginStore(appDirs.modIoLoginFile),
    private val modIoApiUrl: String = ModIoClient.DEFAULT_API_URL,
    val modwerkstattLogins: ModwerkstattLoginStore = ModwerkstattLoginStore(appDirs.modwerkstattLoginFile),
    private val modwerkstattApiUrl: String = ModwerkstattClient.DEFAULT_API_URL,
    /** Which URLs get the modwerkstatt.com session token; only modwerkstatt.com over HTTPS, tests widen it. */
    private val sendsModwerkstattToken: (String) -> Boolean = ModwerkstattClient::isModwerkstattUrl,
) {

    /** The mod lists of the sites, with the modwerkstatt.com sign-in when there is one. */
    val feeds: FeedService by lazy { FeedService(appDirs, http, headers = ::feedHeaders) }

    /** Preview pictures of mods on the sites, for the frontends. */
    val images: ImageCache by lazy { ImageCache(appDirs.images, http) }

    init {
        logger.info {
            "Mod folders: local ${gamePaths.localMods ?: "not found"}, staging ${gamePaths.stagingArea ?: "not found"}, " +
                "mod.io ${gamePaths.modIoMods ?: "not found"}"
        }
    }

    /** Where installs go. */
    val modsDir: Path
        get() = gamePaths.localMods ?: throw IllegalStateException(
            "TF3 mod folder not found. Start the game once, or set the folder explicitly.",
        )

    fun scan(locations: Collection<ModLocation> = ModLocation.entries): List<InstalledMod> =
        locations.flatMap { location -> gamePaths.dirFor(location)?.let { ModScanner.scan(it, location) }.orEmpty() }

    fun installArchive(archive: Path): List<InstalledFolder> =
        installer.install(archive, modsDir, archiveSha256 = HttpDownloader.sha256(archive))

    /** Downloads and installs every file of [mod]. [progress] receives the file being downloaded. */
    suspend fun installRemote(mod: RemoteMod, progress: ((RemoteFile) -> DownloadProgress)? = null): List<InstalledFolder> {
        require(mod.files.isNotEmpty()) { "${mod.ref} has no downloadable file" }
        return mod.files.flatMap { file -> installRemoteFile(mod, file, progress?.invoke(file)) }
    }

    suspend fun installRemoteFile(mod: RemoteMod, file: RemoteFile, progress: DownloadProgress? = null): List<InstalledFolder> =
        withContext(Dispatchers.IO) {
            val archive = appDirs.ensureCreated().downloads.resolve(downloadFileName(mod, file))
            val headers = if (mod.source == FeedSource.MODWERKSTATT) modwerkstattHeaders(file.downloadUrl) else emptyMap()
            val sha256 = http.download(file.downloadUrl, archive, file.size, file.sha256, headers, progress)
            val origin = InstallOrigin(
                source = mod.source,
                remoteId = mod.id,
                fileId = file.id,
                fileName = file.fileName,
                remoteChangedAt = file.changedAt,
                version = mod.version,
            )
            installer.install(archive, modsDir, origin, sha256)
        }

    fun uninstall(folderName: String): Path = installer.uninstall(modsDir, folderName)

    /** A client for the signed-in mod.io account, null when not signed in. [language] picks translated names. */
    fun modIo(language: String? = null): ModIoClient? = modIoLogins.load()?.let { ModIoClient(http, it, modIoApiUrl, language) }

    /** Checks [accessToken] with mod.io and stores it. Nothing is stored when mod.io refuses it. */
    suspend fun modIoLogin(accessToken: String): ModIoUser {
        val token = accessToken.trim()
        require(token.isNotEmpty()) { "The access token is empty" }
        val user = ModIoClient(http, ModIoLogin(token), modIoApiUrl).me()
        withContext(Dispatchers.IO) { modIoLogins.save(ModIoLogin(token, user.username)) }
        return user
    }

    /** Forgets the stored token. Returns false when there was none. */
    fun modIoLogout(): Boolean = modIoLogins.clear()

    /** The stored modwerkstatt.com sign-in, null when there is none or it has expired. */
    fun modwerkstattLogin(): ModwerkstattLogin? = modwerkstattLogins.load()?.takeUnless { it.isExpired() }

    /** Asks modwerkstatt.com to mail a sign-in code to [email]; [modwerkstattConfirm] completes the sign-in. */
    suspend fun modwerkstattRequestCode(email: String) {
        require(email.isNotBlank()) { "The email address is empty" }
        modwerkstattClient().requestCode(email)
    }

    /** Exchanges the mailed [code] for a session token and stores it. Nothing is stored when modwerkstatt.com refuses the code. */
    suspend fun modwerkstattConfirm(email: String, code: String, deviceName: String = DEVICE_NAME): ModwerkstattLogin {
        require(code.isNotBlank()) { "The code is empty" }
        val login = modwerkstattClient().confirmCode(email, code, deviceName)
        withContext(Dispatchers.IO) {
            modwerkstattLogins.save(login)
            // Signed in, the list may hold more mods
            feeds.clearCache(FeedSource.MODWERKSTATT)
        }
        return login
    }

    /**
     * The sign-in with the account details modwerkstatt.com has now, null when not signed in. A sign-in
     * modwerkstatt.com no longer accepts is forgotten and [ModwerkstattAuthException] thrown.
     */
    suspend fun modwerkstattAccount(): ModwerkstattLogin? {
        val stored = withContext(Dispatchers.IO) { modwerkstattLogins.load() } ?: return null
        val current = try {
            if (stored.isExpired()) throw ModwerkstattAuthException("The modwerkstatt.com sign-in has expired.")
            modwerkstattClient().me(stored)
        } catch (e: ModwerkstattAuthException) {
            withContext(Dispatchers.IO) { forgetModwerkstattLogin() }
            throw e
        }
        if (current != stored) withContext(Dispatchers.IO) { modwerkstattLogins.save(current) }
        return current
    }

    /**
     * Signs out on modwerkstatt.com, so the token becomes invalid, and forgets it. The token is forgotten
     * also when modwerkstatt.com cannot be reached, see [ModwerkstattLogoutResult.FORGOTTEN_LOCALLY].
     */
    suspend fun modwerkstattLogout(): ModwerkstattLogoutResult {
        val login = withContext(Dispatchers.IO) { modwerkstattLogins.load() } ?: return ModwerkstattLogoutResult.NOT_SIGNED_IN
        var result = ModwerkstattLogoutResult.SIGNED_OUT
        if (!login.isExpired()) {
            try {
                modwerkstattClient().logout(login)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModwerkstattAuthException) {
                // The token is no longer valid there, which is what signing out achieves
                logger.info { "modwerkstatt.com no longer accepted the token when signing out: ${e.message}" }
            } catch (e: Exception) {
                logger.warn(e) { "Could not sign out on modwerkstatt.com, forgetting the token anyway" }
                result = ModwerkstattLogoutResult.FORGOTTEN_LOCALLY
            }
        }
        withContext(Dispatchers.IO) { forgetModwerkstattLogin() }
        return result
    }

    private fun forgetModwerkstattLogin() {
        modwerkstattLogins.clear()
        feeds.clearCache(FeedSource.MODWERKSTATT)
    }

    private fun modwerkstattClient() = ModwerkstattClient(http, modwerkstattApiUrl)

    internal fun feedHeaders(source: FeedSource): Map<String, String> =
        if (source == FeedSource.MODWERKSTATT) modwerkstattHeaders(source.url) else emptyMap()

    /** The session token for requests to [url], when signed in and [url] is on modwerkstatt.com. */
    private fun modwerkstattHeaders(url: String): Map<String, String> {
        if (!sendsModwerkstattToken(url)) return emptyMap()
        return modwerkstattLogin()?.let { mapOf("Authorization" to "Bearer ${it.token}") }.orEmpty()
    }

    /**
     * Subscriptions of the signed-in user compared with the mod.io folder; null when not signed in.
     * [installed] is the scanned mod.io folder, if the caller has it already.
     */
    suspend fun modIoSubscriptions(language: String? = null, installed: List<InstalledMod>? = null): ModIoSubscriptions? {
        val client = modIo(language) ?: return null
        val subscribed = client.subscribedMods()
        return ModIoSubscriptions(subscribed, installed ?: withContext(Dispatchers.IO) { scan(listOf(ModLocation.MOD_IO)) })
    }

    /** Mods installed from a feed whose entry now offers a different file. Skips mods in [changedOutside]. */
    fun availableUpdates(feeds: List<FeedResult>): List<AvailableUpdate> {
        val remoteByRef = feeds.flatMap { it.mods }.associateBy { it.ref }
        val changed = changedOutside().map { it.record.folderName }.toSet()
        return registry.records(modsDir).values.filter { it.folderName !in changed }.mapNotNull { record ->
            val origin = record.origin
            val remote = remoteByRef[origin.ref] ?: return@mapNotNull null
            // An entry can hold several mod folders; match ours by name
            val file = remote.files.firstOrNull { it.folderName == record.folderName } ?: remote.files.singleOrNull()
                ?: return@mapNotNull null
            val newer = if (file.sha256 != null && record.archiveSha256 != null) {
                // transportfever.net has checksums: a re-upload of the same archive is no update
                !file.sha256.equals(record.archiveSha256, ignoreCase = true)
            } else {
                file.id != origin.fileId ||
                    (file.changedAt != null && origin.remoteChangedAt != null && file.changedAt > origin.remoteChangedAt)
            }
            if (newer) AvailableUpdate(record, remote, file) else null
        }
    }

    /** Mods installed with shunter whose folder now holds a different mod id or revision, or is gone. */
    fun changedOutside(): List<ChangedOutside> = registry.records(modsDir).values.mapNotNull { record ->
        val folder = modsDir.resolve(record.folderName)
        if (!folder.isDirectory()) return@mapNotNull ChangedOutside(record, null)
        val current = ModScanner.read(folder, ModLocation.LOCAL)
        if (current.modId == record.modId && current.manifest?.revision == record.revision) null else ChangedOutside(record, current)
    }

    /** Required `mod.json` dependencies of [mods] that no mod in [available] satisfies. */
    fun missingDependencies(mods: List<InstalledMod>, available: List<InstalledMod> = scan()): List<MissingDependency> {
        val revisionsById = available.mapNotNull { m -> m.manifest?.let { it.modId to (it.revision ?: 0) } }
            .groupBy({ it.first }, { it.second })
        return mods.flatMap { mod ->
            mod.manifest?.dependencies.orEmpty()
                .filter { !it.optional }
                .mapNotNull { dep ->
                    val revisions = revisionsById[dep.mod.modId]
                    when {
                        revisions == null -> MissingDependency(mod, dep.mod, dep.displayName)
                        revisions.none { dep.mod.matches(it) } -> MissingDependency(mod, dep.mod, dep.displayName, revisions.first())
                        else -> null
                    }
                }
        }
    }

    /** Mod ids present in more than one folder; the game would see them twice. */
    fun duplicateModIds(mods: List<InstalledMod> = scan()): Map<String, List<InstalledMod>> =
        mods.filter { it.modId != null }.groupBy { it.modId!! }.filterValues { it.size > 1 }

    private fun downloadFileName(mod: RemoteMod, file: RemoteFile): String {
        val safeName = file.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "${mod.source.id}-${mod.id}-${file.id}-$safeName"
    }

    companion object {
        /** How a modwerkstatt.com sign-in shows up in the account's list of devices. */
        val DEVICE_NAME = "shunter (${System.getProperty("os.name")})"

        fun findRemote(feeds: List<FeedResult>, source: FeedSource, id: String): RemoteMod? =
            feeds.firstOrNull { it.source == source }?.mods?.firstOrNull { it.id == id }
    }
}
