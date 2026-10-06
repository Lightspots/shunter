package ch.lightspots.shunter.core

import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException

/**
 * The message to show for an exception. File system exceptions often have just the path as their
 * message, without saying what went wrong; for them the reason is added.
 */
fun Throwable.readableMessage(): String {
    if (this !is FileSystemException) return message ?: toString()
    val paths = listOfNotNull(file, otherFile).joinToString(" -> ")
    return when {
        this is AccessDeniedException -> "Access denied: $paths"
        this is NoSuchFileException -> "Not found: $paths"
        reason == null -> "${javaClass.simpleName}: $paths"
        else -> message ?: toString()
    }
}
