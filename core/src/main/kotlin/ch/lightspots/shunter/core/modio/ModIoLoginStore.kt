package ch.lightspots.shunter.core.modio

import ch.lightspots.shunter.core.stateJson
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val logger = KotlinLogging.logger {}

/** A mod.io OAuth access token and the account it belongs to. [toString] leaves the token out, so it never ends up in a log. */
@Serializable
data class ModIoLogin(val accessToken: String, val username: String? = null) {
    override fun toString() = "ModIoLogin(username=$username)"
}

/**
 * Keeps the access token in a file only the user can read (mode 600).
 * Writes go through a temporary file that has these permissions from the start, then replace the file.
 */
class ModIoLoginStore(val file: Path) {

    /** The stored login, or null when there is none or the file cannot be read. */
    fun load(): ModIoLogin? {
        if (!file.exists()) return null
        return runCatching { stateJson.decodeFromString<ModIoLogin>(file.readText()) }
            .onFailure { logger.warn { "Ignoring unreadable mod.io login in $file: ${it.javaClass.simpleName}" } }
            .getOrNull()
            ?.takeIf { it.accessToken.isNotBlank() }
    }

    fun save(login: ModIoLogin) {
        file.parent.createDirectories()
        val temp = file.resolveSibling("${file.fileName}.tmp")
        temp.deleteIfExists()
        if (file.fileSystem.supportedFileAttributeViews().contains("posix")) {
            Files.createFile(temp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        }
        try {
            temp.writeText(stateJson.encodeToString(login))
            temp.moveTo(file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.deleteIfExists()
        }
        logger.info { "Stored mod.io login for ${login.username ?: "unknown user"} in $file" }
    }

    /** Forgets the token. Returns false when none was stored. */
    fun clear(): Boolean = file.deleteIfExists().also { if (it) logger.info { "Removed mod.io login $file" } }
}
