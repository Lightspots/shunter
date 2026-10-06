package ch.lightspots.shunter.core.net

import ch.lightspots.shunter.core.BuildInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.content.TextContent
import io.ktor.http.contentLength
import io.ktor.http.formUrlEncode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.moveTo
import kotlin.io.path.outputStream

private val logger = KotlinLogging.logger {}

open class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The server answered with an error status. [body] holds the start of the response, which APIs use to explain the error. */
class HttpStatusException(val status: Int, val url: String, val body: String? = null) : DownloadException("HTTP $status for $url")

/** A successful response read as text. */
data class HttpText(val status: Int, val text: String)

/** Called on a background thread while downloading. [total] is null when the server does not send a length. */
fun interface DownloadProgress {
    fun update(downloaded: Long, total: Long?)
}

/**
 * Ktor client for feeds and downloads. Calls are cancellable: cancelling a download stops it and
 * removes the partial file. Owns its [client], so [close] it when done.
 */
class HttpDownloader(private val client: HttpClient = defaultClient()) : AutoCloseable {

    /** [headers] are sent with the request but never logged, so they may carry credentials. */
    suspend fun fetchText(url: String, headers: Map<String, String> = emptyMap()): String = request(HttpMethod.Get, url, headers).text

    /**
     * Sends a request and reads the answer as text, for APIs. [form] is sent as
     * `application/x-www-form-urlencoded` body, without a charset parameter: mod.io refuses the
     * `; charset=UTF-8` Ktor's `FormDataContent` adds (HTTP 415). [headers] are never logged. An error status throws
     * [HttpStatusException] with the start of the body.
     */
    suspend fun request(
        method: HttpMethod,
        url: String,
        headers: Map<String, String> = emptyMap(),
        form: Map<String, String>? = null,
    ): HttpText {
        logger.debug { "${method.value} $url" }
        val response = client.request(url) {
            this.method = method
            headers.forEach { (name, value) -> header(name, value) }
            form?.let { fields ->
                val encoded = Parameters.build { fields.forEach { (name, value) -> append(name, value) } }.formUrlEncode()
                setBody(TextContent(encoded, ContentType.Application.FormUrlEncoded))
            }
        }
        val text = response.bodyAsText(Charsets.UTF_8)
        if (!response.status.isSuccess()) throw HttpStatusException(response.status.value, url, text.take(MAX_ERROR_BODY))
        return HttpText(response.status.value, text)
    }

    /**
     * Downloads [url] to [dest]. Size and SHA-256 are verified when given; a mismatching file is
     * deleted and the call fails. An existing [dest] with the expected checksum is reused.
     * Returns the SHA-256 of the file.
     */
    suspend fun download(
        url: String,
        dest: Path,
        expectedSize: Long? = null,
        expectedSha256: String? = null,
        progress: DownloadProgress? = null,
    ): String = withContext(Dispatchers.IO) {
        if (expectedSha256 != null && dest.exists() && sha256(dest).equals(expectedSha256, ignoreCase = true)) {
            logger.info { "Using $dest downloaded before, checksum matches" }
            return@withContext expectedSha256.lowercase()
        }
        dest.parent.createDirectories()
        val part = dest.resolveSibling("${dest.fileName}.part")
        logger.info { "Downloading $url to $dest" }
        try {
            client.prepareGet(url).execute { response ->
                checkStatus(url, response)
                val total = response.contentLength() ?: expectedSize
                val body = response.bodyAsChannel()
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        // readAvailable does not suspend while data is buffered, so check explicitly
                        currentCoroutineContext().ensureActive()
                        val read = body.readAvailable(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        progress?.update(downloaded, total)
                    }
                }
                if (expectedSize != null && downloaded != expectedSize) {
                    throw DownloadException("Download incomplete: expected $expectedSize bytes but got $downloaded ($url)")
                }
                val actual = HexFormat.of().formatHex(digest.digest())
                if (expectedSha256 != null && !actual.equals(expectedSha256, ignoreCase = true)) {
                    throw DownloadException("Checksum mismatch for $url: expected $expectedSha256 but got $actual")
                }
                part.moveTo(dest, StandardCopyOption.REPLACE_EXISTING)
                logger.info { "Downloaded ${dest.fileName}: $downloaded bytes, sha256 $actual" }
                actual
            }
        } finally {
            part.deleteIfExists()
        }
    }

    override fun close() = client.close()

    private fun checkStatus(url: String, response: HttpResponse) {
        if (!response.status.isSuccess()) throw HttpStatusException(response.status.value, url)
    }

    companion object {
        private const val MAX_ERROR_BODY = 4000

        /** Follows redirects (Ktor's default). No overall request timeout, so large files can take their time. */
        fun defaultClient(userAgent: String = "shunter/${BuildInfo.VERSION}"): HttpClient = HttpClient(CIO) {
            install(UserAgent) { agent = userAgent }
            install(HttpTimeout) {
                connectTimeoutMillis = 20_000
                // Time without any data, not the whole transfer
                socketTimeoutMillis = 60_000
            }
        }

        fun sha256(file: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return HexFormat.of().formatHex(digest.digest())
        }
    }
}
