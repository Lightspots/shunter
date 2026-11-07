package ch.lightspots.shunter.core.net

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.writeBytes

private val logger = KotlinLogging.logger {}

/**
 * Preview pictures of mods on the sites, kept in [dir] so they are fetched once. Only HTTPS addresses are fetched
 * (tests widen [allowsUrl]), a few at a time, and at most [maxBytes] each. The frontends decode the files; a picture
 * that cannot be fetched is not asked for again until the app restarts.
 */
class ImageCache(
    private val dir: Path,
    private val http: HttpDownloader,
    private val maxBytes: Long = 5_000_000,
    private val allowsUrl: (String) -> Boolean = { it.startsWith("https://") },
    private val clock: Clock = Clock.systemUTC(),
) {
    private val parallel = Semaphore(4)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /** The picture at [url] as a local file, fetched if needed. Null when it cannot be had. */
    suspend fun get(url: String): Path? {
        if (!allowsUrl(url) || url in failed) return null
        val file = fileFor(url)
        if (withContext(Dispatchers.IO) { file.isRegularFile() }) return file
        // One fetch per picture, even when several rows show it
        return locks.computeIfAbsent(url) { Mutex() }.withLock {
            if (withContext(Dispatchers.IO) { file.isRegularFile() }) return@withLock file
            if (url in failed) return@withLock null
            try {
                val bytes = parallel.withPermit { http.fetchBytes(url, maxBytes) }
                withContext(Dispatchers.IO) {
                    dir.createDirectories()
                    val part = file.resolveSibling("${file.fileName}.part")
                    part.writeBytes(bytes)
                    part.moveTo(file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }
                file
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.debug { "No preview picture from ${redactUrl(url)}: ${e.message}" }
                failed += url
                null
            }
        }
    }

    /** Deletes pictures not fetched for [maxAge]; mods change their pictures and some leave the sites. */
    fun prune(maxAge: Duration = Duration.ofDays(30)) {
        if (!dir.isDirectory()) return
        val oldest = clock.instant().minus(maxAge)
        val removed = dir.listDirectoryEntries().count { file ->
            runCatching { file.getLastModifiedTime().toInstant().isBefore(oldest) && file.deleteIfExists() }.getOrDefault(false)
        }
        if (removed > 0) logger.info { "Removed $removed old preview pictures" }
    }

    /** A name from the address, keeping a known extension so the file can be opened as what it is. */
    private fun fileFor(url: String): Path {
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(url.toByteArray())).take(32)
        val extension = url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return dir.resolve(if (extension in EXTENSIONS) "$hash.$extension" else hash)
    }

    private companion object {
        val EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif")
    }
}
