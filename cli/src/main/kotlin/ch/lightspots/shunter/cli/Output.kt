package ch.lightspots.shunter.cli

import ch.lightspots.shunter.core.net.DownloadProgress
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun humanSize(bytes: Long?): String {
    if (bytes == null) return "?"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes B" else "%.1f %s".format(value, units[unit])
}

private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

internal fun date(epochSeconds: Long?): String = epochSeconds?.let { DATE.format(Instant.ofEpochSecond(it)) } ?: "?"

/** Formats rows as left-aligned columns; the last column is not padded. */
internal fun table(header: List<String>, rows: List<List<String>>): String {
    val all = listOf(header) + rows
    val widths = header.indices.map { col -> all.maxOf { it.getOrElse(col) { "" }.length } }
    return all.joinToString("\n") { row ->
        row.mapIndexed { col, cell -> if (col == row.lastIndex) cell else cell.padEnd(widths[col]) }
            .joinToString("  ")
            .trimEnd()
    }
}

/** Prints download progress on a single line, updating at most once per percent. Silent when not on a terminal. */
internal class ConsoleProgress(private val label: String) : DownloadProgress {
    private val interactive = System.console() != null
    private var lastPercent = -1

    override fun update(downloaded: Long, total: Long?) {
        if (!interactive || total == null || total <= 0) return
        val percent = (downloaded * 100 / total).toInt()
        if (percent == lastPercent) return
        lastPercent = percent
        print("\r  $label: $percent% (${humanSize(downloaded)} / ${humanSize(total)})")
        if (downloaded >= total) println()
        System.out.flush()
    }
}
