package ch.lightspots.shunter.cli

import ch.lightspots.shunter.core.net.DownloadProgress
import com.github.ajalt.mordant.animation.progress.ThreadProgressTaskAnimator
import com.github.ajalt.mordant.animation.progress.animateOnThread
import com.github.ajalt.mordant.animation.progress.execute
import com.github.ajalt.mordant.rendering.TextAlign
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.progress.completed
import com.github.ajalt.mordant.widgets.progress.marquee
import com.github.ajalt.mordant.widgets.progress.percentage
import com.github.ajalt.mordant.widgets.progress.progressBar
import com.github.ajalt.mordant.widgets.progress.progressBarLayout
import com.github.ajalt.mordant.widgets.progress.speed
import com.github.ajalt.mordant.widgets.progress.timeRemaining
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Future

/** Decimal units (1 MB = 1000 KB), like the progress bar. */
internal fun humanSize(bytes: Long?): String {
    if (bytes == null) return "?"
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

internal fun date(epochSeconds: Long?): String = epochSeconds?.let { DATE.format(Instant.ofEpochSecond(it)) } ?: "?"

/** Color codes (SGR) as Mordant writes them; they take no space on the screen. */
private val STYLE_CODES = Regex("\u001B\\[[0-9;]*m")

private fun visibleLength(text: String) = text.replace(STYLE_CODES, "").length

/** Formats rows as left-aligned columns with a bold header; the last column is not padded. Cells may be styled. */
internal fun table(header: List<String>, rows: List<List<String>>): String {
    val all = listOf(header.map { TextStyles.bold.style(it) }) + rows
    val widths = header.indices.map { col -> all.maxOf { visibleLength(it.getOrElse(col) { "" }) } }
    return all.joinToString("\n") { row ->
        row.mapIndexed { col, cell -> if (col == row.lastIndex) cell else cell + " ".repeat(widths[col] - visibleLength(cell)) }
            .joinToString("  ")
            .trimEnd()
    }
}

/**
 * Download progress bar. It appears with the first update, so a download that is skipped (file already
 * downloaded) shows nothing, and Mordant draws nothing when the output is not a terminal.
 * [close] stops it, also when the download failed halfway.
 */
internal class DownloadBar(private val terminal: Terminal, private val label: String) :
    DownloadProgress,
    AutoCloseable {
    private val lock = Any()
    private var bar: ThreadProgressTaskAnimator<Unit>? = null
    private var drawing: Future<*>? = null

    override fun update(downloaded: Long, total: Long?) = synchronized(lock) {
        (bar ?: start()).update {
            this.total = total
            completed = downloaded
        }
    }

    private fun start(): ThreadProgressTaskAnimator<Unit> = progressBarLayout {
        marquee(label, width = 30, align = TextAlign.LEFT)
        percentage()
        progressBar()
        completed(suffix = "B")
        speed("B/s")
        timeRemaining(elapsedWhenFinished = true)
    }.animateOnThread(terminal).also {
        bar = it
        drawing = it.execute()
    }

    override fun close() {
        synchronized(lock) {
            val bar = bar ?: return
            // Let it draw the final 100% frame; stop() also shows the cursor again
            if (bar.finished) runCatching { drawing?.get() }
            bar.stop()
        }
    }
}
