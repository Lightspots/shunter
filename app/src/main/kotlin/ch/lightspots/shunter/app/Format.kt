package ch.lightspots.shunter.app

import ch.lightspots.shunter.core.mod.ModReference
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Decimal units (1 MB = 1000 KB). */
fun humanSize(bytes: Long?): String? {
    if (bytes == null) return null
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    return if (unit == 0) "$bytes B" else "%.1f %s".format(value, units[unit])
}

private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

fun date(epochSeconds: Long?): String? = epochSeconds?.let { DATE.format(Instant.ofEpochSecond(it)) }

/** "12 min ago" style age of [instant]. */
fun ago(instant: Instant, now: Instant = Instant.now()): String {
    val minutes = Duration.between(instant, now).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

/** "revision 2 or later" style text for the allowed revisions; empty when any revision will do. */
fun ModReference.revisionText(): String = when {
    revisionMin != null && revisionMax != null && revisionMin == revisionMax -> "revision $revisionMin"
    revisionMin != null && revisionMax != null -> "revision $revisionMin to $revisionMax"
    revisionMin != null -> "revision $revisionMin or later"
    revisionMax != null -> "revision $revisionMax or earlier"
    else -> ""
}

/** "common_api ≥ 2" style short form. */
fun ModReference.shortText(): String = when {
    revisionMin != null && revisionMax != null -> "$modId $revisionMin–$revisionMax"
    revisionMin != null -> "$modId ≥ $revisionMin"
    revisionMax != null -> "$modId ≤ $revisionMax"
    else -> modId
}

/** Up to two initials of [name], for placeholder thumbnails. */
fun initials(name: String): String = name.replace(Regex("[^\\p{L}\\p{N} ]"), " ")
    .split(' ')
    .filter { it.isNotBlank() }
    .take(2)
    .joinToString("") { it.first().uppercase() }
    .ifEmpty { "?" }

/** The last [segments] parts of [path] with a leading "…/", so long paths fit the sidebar. */
fun shortPath(path: String, segments: Int = 3): String {
    val parts = path.trimEnd('/').split('/')
    return if (parts.size <= segments + 1) path else "…/" + parts.takeLast(segments).joinToString("/")
}
