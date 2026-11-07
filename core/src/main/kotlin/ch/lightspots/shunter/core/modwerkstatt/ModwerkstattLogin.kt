package ch.lightspots.shunter.core.modwerkstatt

import ch.lightspots.shunter.core.stateJson
import ch.lightspots.shunter.core.writePrivateFile
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText

private val logger = KotlinLogging.logger {}

/** A modwerkstatt.com session token and the account it belongs to. [toString] leaves the token out, so it never ends up in a log. */
@Serializable
data class ModwerkstattLogin(
    val token: String,
    val email: String,
    val name: String? = null,
    /** Membership end as modwerkstatt.com sends it (a date), null without membership. */
    val membershipUntil: String? = null,
    /** Epoch seconds. */
    val expiresAt: Long,
) {
    fun isExpired(now: Instant = Instant.now()) = now.epochSecond >= expiresAt

    override fun toString() = "ModwerkstattLogin(email=$email, name=$name, membershipUntil=$membershipUntil, expiresAt=$expiresAt)"
}

/** Keeps the session token in a file only the user can read (mode 600). */
class ModwerkstattLoginStore(val file: Path) {

    /** The stored login, also an expired one; null when there is none or the file cannot be read. */
    fun load(): ModwerkstattLogin? {
        if (!file.exists()) return null
        return runCatching { stateJson.decodeFromString<ModwerkstattLogin>(file.readText()) }
            .onFailure { logger.warn { "Ignoring unreadable modwerkstatt.com login in $file: ${it.javaClass.simpleName}" } }
            .getOrNull()
            ?.takeIf { it.token.isNotBlank() }
    }

    fun save(login: ModwerkstattLogin) {
        writePrivateFile(file, stateJson.encodeToString(login))
        logger.info { "Stored modwerkstatt.com login for ${login.name ?: login.email} in $file" }
    }

    /** Forgets the token. Returns false when none was stored. */
    fun clear(): Boolean = file.deleteIfExists().also { if (it) logger.info { "Removed modwerkstatt.com login $file" } }
}
