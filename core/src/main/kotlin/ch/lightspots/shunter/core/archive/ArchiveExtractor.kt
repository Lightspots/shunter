package ch.lightspots.shunter.core.archive

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.outputStream

class ArchiveException(message: String, cause: Throwable? = null) : IOException(message, cause)

enum class ArchiveFormat { ZIP, SEVEN_ZIP }

/**
 * Extracts mod archives (.zip and .7z; transportfever.net serves both).
 *
 * Archives come from the internet, so every entry is checked: absolute paths, `..` segments and
 * symlinks are rejected instead of being written somewhere outside the target folder.
 */
object ArchiveExtractor {

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B) // "PK"
    private val SEVEN_ZIP_MAGIC = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
    private val RAR_MAGIC = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) // "Rar!\u001A\u0007"

    /** Detects the format from the file content; download URLs and file names are not reliable. */
    fun detectFormat(archive: Path): ArchiveFormat {
        val header = archive.inputStream().use { it.readNBytes(8) }
        return when {
            header.startsWith(SEVEN_ZIP_MAGIC) -> ArchiveFormat.SEVEN_ZIP
            header.startsWith(ZIP_MAGIC) -> ArchiveFormat.ZIP
            header.startsWith(RAR_MAGIC) -> throw ArchiveException("RAR archives are not supported yet: $archive")
            else -> throw ArchiveException("Unknown archive format: $archive")
        }
    }

    /** Extracts [archive] into [target], which must not exist yet or be empty. */
    fun extract(archive: Path, target: Path) {
        if (target.exists() && (!target.isDirectory() || target.listDirectoryEntries().isNotEmpty())) {
            throw ArchiveException("Extraction target is not empty: $target")
        }
        target.createDirectories()
        val root = target.toRealPath()
        try {
            when (detectFormat(archive)) {
                ArchiveFormat.ZIP -> extractZip(archive, root)
                ArchiveFormat.SEVEN_ZIP -> extractSevenZip(archive, root)
            }
        } catch (e: ArchiveException) {
            throw e
        } catch (e: IOException) {
            throw ArchiveException("Failed to extract $archive: ${e.message}", e)
        }
    }

    private fun extractZip(archive: Path, root: Path) {
        ZipFile.builder().setPath(archive).get().use { zip ->
            for (entry in zip.entries) {
                if (entry.isUnixSymlink) throw ArchiveException("Archive contains a symlink, refusing: ${entry.name}")
                val dest = resolveEntry(root, entry.name) ?: continue
                if (entry.isDirectory) {
                    dest.createDirectories()
                } else {
                    zip.getInputStream(entry).use { write(it, dest) }
                }
            }
        }
    }

    private fun extractSevenZip(archive: Path, root: Path) {
        SevenZFile.builder().setPath(archive).get().use { sevenZip ->
            // Sequential reading is much faster than random access for solid 7z archives
            var entry = sevenZip.nextEntry
            while (entry != null) {
                val dest = if (entry.isAntiItem) null else resolveEntry(root, entry.name)
                if (dest != null) {
                    if (entry.isDirectory) {
                        dest.createDirectories()
                    } else {
                        write(SevenZipEntryStream(sevenZip), dest)
                    }
                }
                entry = sevenZip.nextEntry
            }
        }
    }

    private fun write(input: InputStream, dest: Path) {
        dest.parent.createDirectories()
        dest.outputStream().use { input.copyTo(it) }
    }

    /**
     * Maps an archive entry name to a path below [root]. Returns null for entries to skip
     * (the archive root itself, macOS resource forks) and throws for unsafe names.
     */
    internal fun resolveEntry(root: Path, rawName: String): Path? {
        // Archives made on Windows may use backslashes
        val name = rawName.replace('\\', '/')
        if (name.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(name)) {
            throw ArchiveException("Archive entry has an absolute path, refusing: $rawName")
        }
        val segments = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return null
        if (segments.any { it == ".." }) throw ArchiveException("Archive entry escapes the target folder, refusing: $rawName")
        if (segments.first() == "__MACOSX") return null

        val dest = segments.fold(root) { path, segment -> path.resolve(segment) }.normalize()
        if (!dest.startsWith(root)) throw ArchiveException("Archive entry escapes the target folder, refusing: $rawName")
        return dest
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    /** Exposes the current entry of a [SevenZFile] as a stream without closing the archive. */
    private class SevenZipEntryStream(private val file: SevenZFile) : InputStream() {
        override fun read(): Int = file.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = file.read(b, off, len)
    }
}
