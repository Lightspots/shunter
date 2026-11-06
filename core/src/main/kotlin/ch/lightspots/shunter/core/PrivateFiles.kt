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
 * temporary file that has these permissions from the start, which then replaces [file].
 */
internal fun writePrivateFile(file: Path, text: String) {
    file.parent.createDirectories()
    val temp = file.resolveSibling("${file.fileName}.tmp")
    temp.deleteIfExists()
    if (file.fileSystem.supportedFileAttributeViews().contains("posix")) {
        Files.createFile(temp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    }
    try {
        temp.writeText(text)
        temp.moveTo(file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        temp.deleteIfExists()
    }
}
