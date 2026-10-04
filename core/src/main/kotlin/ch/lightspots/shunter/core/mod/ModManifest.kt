package ch.lightspots.shunter.core.mod

import ch.lightspots.shunter.core.arr
import ch.lightspots.shunter.core.bool
import ch.lightspots.shunter.core.get
import ch.lightspots.shunter.core.int
import ch.lightspots.shunter.core.parseJsonFile
import ch.lightspots.shunter.core.parseJsonText
import ch.lightspots.shunter.core.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

/** How bad adding or removing a mod is for an existing savegame (`severityAdd` / `severityRemove`). */
enum class Severity {
    NONE, WARNING, CRITICAL;

    companion object {
        fun parse(value: String?): Severity? = when (value?.trim()?.lowercase()) {
            "none" -> NONE
            "warning" -> WARNING
            "critical" -> CRITICAL
            else -> null
        }
    }
}

/** Reference to another mod with an optional allowed revision range. */
data class ModReference(
    val modId: String,
    val revisionMin: Int? = null,
    val revisionMax: Int? = null,
) {
    fun matches(revision: Int): Boolean =
        (revisionMin == null || revision >= revisionMin) && (revisionMax == null || revision <= revisionMax)
}

data class ModDependency(
    val mod: ModReference,
    val displayName: String? = null,
    val url: String? = null,
    val loadBefore: Boolean = false,
    val optional: Boolean = false,
)

/** The game-relevant part of a mod's `mod.json`. */
data class ModManifest(
    val modId: String,
    val revision: Int? = null,
    val severityAdd: Severity? = null,
    val severityRemove: Severity? = null,
    val visible: Boolean = true,
    val cosmetic: Boolean = false,
    val dependencies: List<ModDependency> = emptyList(),
    val incompatibilities: List<ModReference> = emptyList(),
) {
    companion object {
        const val FILE_NAME = "mod.json"

        fun read(path: Path): ModManifest = from(parseJsonFile(path))

        fun parse(text: String): ModManifest = from(parseJsonText(text))

        fun from(json: JsonElement): ModManifest {
            require(json is JsonObject) { "mod.json is not a JSON object" }
            val modId = readModId(json["modId"])
            require(!modId.isNullOrBlank()) { "mod.json has no modId" }
            return ModManifest(
                modId = modId,
                revision = json["revision"].int(),
                severityAdd = Severity.parse(json["severityAdd"].str()),
                severityRemove = Severity.parse(json["severityRemove"].str()),
                visible = json["visible"].bool() ?: true,
                cosmetic = json["cosmetic"].bool() ?: false,
                // "dependencies": null is valid and common
                dependencies = json["dependencies"].arr().mapNotNull(::readDependency),
                incompatibilities = json["incompatibilities"].arr().mapNotNull { readReference(it["mod"] ?: it) },
            )
        }

        private fun readDependency(json: JsonElement): ModDependency? {
            // Usually { "mod": { "modId": ... }, "modInfo": {...}, ... } but a flat reference is accepted too
            val reference = readReference(json["mod"] ?: json) ?: return null
            return ModDependency(
                mod = reference,
                displayName = json["modInfo"]["displayName"].str()?.takeIf { it.isNotBlank() },
                url = json["modInfo"]["url"].str()?.takeIf { it.isNotBlank() },
                loadBefore = json["loadBefore"].bool() ?: false,
                optional = json["optional"].bool() ?: false,
            )
        }

        private fun readReference(json: JsonElement): ModReference? {
            val modId = readModId(json["modId"]) ?: json.str()
            if (modId.isNullOrBlank()) return null
            return ModReference(
                modId = modId.trim(),
                // -1 is used as "no limit"
                revisionMin = json["revisionMin"].int()?.takeIf { it >= 0 },
                revisionMax = json["revisionMax"].int()?.takeIf { it >= 0 },
            )
        }

        /** modId is normally a string, but some files use `{ "name": "..." }`. */
        private fun readModId(json: JsonElement?): String? = (json.str() ?: json["name"].str())?.trim()
    }
}
