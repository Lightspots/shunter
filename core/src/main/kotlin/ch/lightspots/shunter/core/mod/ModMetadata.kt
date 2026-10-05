package ch.lightspots.shunter.core.mod

import ch.lightspots.shunter.core.arr
import ch.lightspots.shunter.core.get
import ch.lightspots.shunter.core.obj
import ch.lightspots.shunter.core.parseJsonFile
import ch.lightspots.shunter.core.parseJsonText
import ch.lightspots.shunter.core.str
import kotlinx.serialization.json.JsonElement
import java.nio.file.Path

data class LocalizedText(val name: String? = null, val summary: String? = null, val description: String? = null)

data class ModAuthor(val name: String, val role: String? = null)

/** Display information from `_metadata/modinfo.json`. Not every mod has it (mod.io packages don't). */
data class ModMetadata(
    val default: LocalizedText,
    val localization: Map<String, LocalizedText> = emptyMap(),
    val authors: List<ModAuthor> = emptyList(),
    val tags: List<String> = emptyList(),
    val url: String? = null,
) {
    /** Text in the requested language, falling back field by field to the default text. */
    fun localized(language: String?): LocalizedText {
        val local = language?.let { localization[it.lowercase()] } ?: return default
        return LocalizedText(
            name = local.name ?: default.name,
            summary = local.summary ?: default.summary,
            description = local.description ?: default.description,
        )
    }

    companion object {
        const val DIR_NAME = "_metadata"
        const val FILE_NAME = "modinfo.json"
        const val PREVIEW_FILE_NAME = "0.png"

        fun read(path: Path): ModMetadata = from(parseJsonFile(path))

        fun parse(text: String): ModMetadata = from(parseJsonText(text))

        fun from(json: JsonElement): ModMetadata = ModMetadata(
            default = readText(json),
            localization = json["localization"].obj().orEmpty()
                .mapKeys { it.key.lowercase() }
                .mapValues { readText(it.value) },
            authors = json["authors"].arr().mapNotNull { author ->
                (author["name"].str() ?: author.str())?.takeIf { it.isNotBlank() }
                    ?.let { ModAuthor(it, author["role"].str()) }
            },
            tags = json["tags"].arr().mapNotNull { (it.str() ?: it["name"].str())?.takeIf(String::isNotBlank) },
            url = json["url"].str()?.takeIf { it.isNotBlank() },
        )

        private fun readText(json: JsonElement) = LocalizedText(
            name = json["name"].str()?.takeIf { it.isNotBlank() },
            summary = json["summary"].str()?.takeIf { it.isNotBlank() },
            description = json["description"].str()?.takeIf { it.isNotBlank() },
        )
    }
}
