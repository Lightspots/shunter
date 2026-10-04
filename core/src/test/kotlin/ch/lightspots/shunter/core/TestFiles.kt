package ch.lightspots.shunter.core

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream
import kotlin.io.path.writeText

/** Builders for mod folders and archives used in tests. */
object TestFiles {

    fun modJson(modId: String, revision: Int = 1, dependencies: String = "null") = """
        {
            "dependencies": $dependencies,
            "incompatibilities": null,
            "modId": "$modId",
            "revision": $revision,
            "severityAdd": "None",
            "severityRemove": "Warning"
        }
    """.trimIndent()

    /** Files of a minimal mod, relative to its folder. */
    fun modFiles(modId: String, revision: Int = 1, name: String = modId, extra: Map<String, String> = emptyMap()) =
        mapOf(
            "mod.json" to modJson(modId, revision),
            "_metadata/modinfo.json" to """{ "name": "$name", "summary": "", "description": "" }""",
            "content/readme.txt" to "revision $revision",
        ) + extra

    fun writeTree(root: Path, files: Map<String, String>) {
        for ((name, text) in files) {
            val file = root.resolve(name)
            file.parent.createDirectories()
            file.writeText(text)
        }
    }

    /** Writes a zip; entry names are used verbatim, so malicious names can be tested too. */
    fun zip(target: Path, files: Map<String, String>): Path {
        target.parent.createDirectories()
        ZipOutputStream(target.outputStream()).use { zip ->
            for ((name, text) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return target
    }

    fun sevenZip(target: Path, files: Map<String, String>): Path {
        target.parent.createDirectories()
        SevenZOutputFile(target.toFile()).use { archive ->
            for ((name, text) in files) {
                val entry = SevenZArchiveEntry()
                entry.name = name
                entry.isDirectory = false
                archive.putArchiveEntry(entry)
                archive.write(text.toByteArray())
                archive.closeArchiveEntry()
            }
        }
        return target
    }

    /** Prefixes every path with [folder], like an archive that contains the mod folder. */
    fun inFolder(folder: String, files: Map<String, String>) = files.mapKeys { "$folder/${it.key}" }
}
