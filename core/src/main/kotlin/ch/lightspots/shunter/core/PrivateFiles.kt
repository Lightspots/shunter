package ch.lightspots.shunter.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.moveTo
import kotlin.io.path.writeText

/**
 * Writes [text] to a file only the user can read (mode 600), for credentials. The text goes to a
 * temporary file of its own that has these permissions from the start, which then replaces [file].
 * The temporary file has a unique name, so processes writing [file] at the same time never share one.
 */
internal fun writePrivateFile(file: Path, text: String) {
    val dir = file.toAbsolutePath().parent.createDirectories()
    val prefix = "${file.fileName}."
    val temp = if (dir.fileSystem.supportedFileAttributeViews().contains("posix")) {
        Files.createTempFile(dir, prefix, ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    } else {
        Files.createTempFile(dir, prefix, ".tmp")
    }
    try {
        temp.writeText(text)
        temp.moveTo(file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        temp.deleteIfExists()
    }
}
