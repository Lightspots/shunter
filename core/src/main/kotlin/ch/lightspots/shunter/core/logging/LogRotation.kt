package ch.lightspots.shunter.core.logging

import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.moveTo
import kotlin.io.path.name

/**
 * Rotation for log files, done once at startup before the file is opened. CLI runs are short and
 * frequent, so a file is only rotated once it is large, not on every start.
 */
object LogRotation {
    const val DEFAULT_MAX_BYTES = 1024L * 1024

    /**
     * Renames [file] (`cli.log`) to `cli.1.log`, `cli.1.log` to `cli.2.log` and so on once [file]
     * has [maxBytes] or more. At most [keep] old files are kept; the oldest is deleted.
     */
    fun rotateIfLarge(file: Path, maxBytes: Long = DEFAULT_MAX_BYTES, keep: Int = 3) {
        require(keep >= 1) { "keep must be at least 1" }
        if (!file.isRegularFile() || file.fileSize() < maxBytes) return
        val base = file.name.removeSuffix(".log")
        fun old(index: Int) = file.resolveSibling("$base.$index.log")

        old(keep).deleteIfExists()
        for (index in keep - 1 downTo 1) {
            if (old(index).exists()) old(index).moveTo(old(index + 1), StandardCopyOption.REPLACE_EXISTING)
        }
        file.moveTo(old(1), StandardCopyOption.REPLACE_EXISTING)
    }
}
