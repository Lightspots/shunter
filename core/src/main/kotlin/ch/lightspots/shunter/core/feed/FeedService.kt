package ch.lightspots.shunter.core.feed

import ch.lightspots.shunter.core.net.HttpDownloader
import ch.lightspots.shunter.core.net.HttpStatusException
import ch.lightspots.shunter.core.paths.AppDirs
import ch.lightspots.shunter.core.writePrivateFile
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val logger = KotlinLogging.logger {}

/**
 * Result of loading a feed. [error] is set when refreshing failed and an older copy was used instead,
 * [signInRefused] when it failed because the site no longer accepts the sign-in.
 */
data class FeedResult(
    val source: FeedSource,
    val mods: List<RemoteMod>,
    val fetchedAt: Instant,
    val error: String? = null,
    val signInRefused: Boolean = false,
)

/** The site refused the sign-in sent with the feed request; signing in again helps. */
class FeedSignInException(val source: FeedSource, val status: Int, cause: Throwable? = null) :
    IOException("${source.label} no longer accepts the sign-in (HTTP $status)", cause)

/** Downloads feeds and keeps the last copy in the cache, so they are not fetched on every command. */
class FeedService(
    private val appDirs: AppDirs,
    private val http: HttpDownloader = HttpDownloader(),
    private val clock: Clock = Clock.systemUTC(),
    /** Extra request headers for a feed, such as a sign-in. They are never logged. */
    private val headers: (FeedSource) -> Map<String, String> = { emptyMap() },
    /** Where each feed is fetched from; tests point it elsewhere. */
    private val urls: (FeedSource) -> String = FeedSource::url,
) {

    suspend fun load(source: FeedSource, maxAge: Duration = Duration.ofHours(1)): FeedResult = withContext(Dispatchers.IO) {
        val cached = cacheFile(source)
        val cachedAt = if (cached.exists()) cached.getLastModifiedTime().toInstant() else null
        if (cachedAt != null && Duration.between(cachedAt, clock.instant()) < maxAge) {
            logger.debug { "Using cached ${source.label} mod list from $cachedAt" }
            return@withContext FeedResult(source, parse(source, cached.readText()), cachedAt)
        }
        try {
            val signedIn = headers(source).isNotEmpty()
            val text = fetch(source)
            // Parse before caching, so a broken response never replaces a good copy
            val mods = parse(source, text)
            cached.parent.createDirectories()
            // Fetched signed in, the list can hold signed download links, which are credentials until they expire
            if (signedIn) writePrivateFile(cached, text) else cached.writeText(text)
            logger.info { "Fetched ${source.label} mod list: ${mods.size} mods" }
            FeedResult(source, mods, clock.instant())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cachedAt == null) throw e
            logger.warn(e) { "Could not refresh ${source.label} mod list, using copy from $cachedAt" }
            FeedResult(source, parse(source, cached.readText()), cachedAt, e.message ?: e.toString(), e is FeedSignInException)
        }
    }

    /** Forgets the cached copy, e.g. after signing in, when the site may list more. */
    fun clearCache(source: FeedSource) {
        if (cacheFile(source).deleteIfExists()) logger.debug { "Removed cached ${source.label} mod list" }
    }

    private suspend fun fetch(source: FeedSource): String {
        val requestHeaders = headers(source)
        return try {
            http.fetchText(urls(source), requestHeaders)
        } catch (e: HttpStatusException) {
            if (requestHeaders.isNotEmpty() && e.status in setOf(401, 403)) throw FeedSignInException(source, e.status, e)
            throw e
        }
    }

    suspend fun loadAll(maxAge: Duration = Duration.ofHours(1)): List<FeedResult> = FeedSource.entries.map { load(it, maxAge) }

    private fun parse(source: FeedSource, text: String): List<RemoteMod> = when (source) {
        FeedSource.TFNET -> TfnetFeedParser.parse(text)
        FeedSource.MODWERKSTATT -> ModwerkstattFeedParser.parse(text)
    }

    private fun cacheFile(source: FeedSource): Path = appDirs.feeds.resolve("${source.id}.json")
}
