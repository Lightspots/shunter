package ch.lightspots.shunter.core.feed

import ch.lightspots.shunter.core.net.HttpDownloader
import ch.lightspots.shunter.core.paths.AppDirs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Result of loading a feed. [error] is set when refreshing failed and an older copy was used instead. */
data class FeedResult(
    val source: FeedSource,
    val mods: List<RemoteMod>,
    val fetchedAt: Instant,
    val error: String? = null,
)

/** Downloads feeds and keeps the last copy in the cache, so they are not fetched on every command. */
class FeedService(
    private val appDirs: AppDirs,
    private val http: HttpDownloader = HttpDownloader(),
    private val clock: Clock = Clock.systemUTC(),
) {

    suspend fun load(source: FeedSource, maxAge: Duration = Duration.ofHours(1)): FeedResult = withContext(Dispatchers.IO) {
        val cached = cacheFile(source)
        val cachedAt = if (cached.exists()) cached.getLastModifiedTime().toInstant() else null
        if (cachedAt != null && Duration.between(cachedAt, clock.instant()) < maxAge) {
            return@withContext FeedResult(source, parse(source, cached.readText()), cachedAt)
        }
        try {
            val text = http.fetchText(source.url)
            // Parse before caching, so a broken response never replaces a good copy
            val mods = parse(source, text)
            cached.parent.createDirectories()
            cached.writeText(text)
            FeedResult(source, mods, clock.instant())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cachedAt == null) throw e
            FeedResult(source, parse(source, cached.readText()), cachedAt, error = e.message ?: e.toString())
        }
    }

    suspend fun loadAll(maxAge: Duration = Duration.ofHours(1)): List<FeedResult> = FeedSource.entries.map { load(it, maxAge) }

    private fun parse(source: FeedSource, text: String): List<RemoteMod> = when (source) {
        FeedSource.TFNET -> TfnetFeedParser.parse(text)
        FeedSource.MODWERKSTATT -> ModwerkstattFeedParser.parse(text)
    }

    private fun cacheFile(source: FeedSource): Path = appDirs.feeds.resolve("${source.id}.json")
}
