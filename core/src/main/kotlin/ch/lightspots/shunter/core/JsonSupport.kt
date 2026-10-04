package ch.lightspots.shunter.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.file.Path
import kotlin.io.path.readBytes

/**
 * JSON written by mod authors and community sites is hand-edited and not always strict,
 * so everything is parsed leniently and read through the tolerant accessors below.
 */
internal val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    allowTrailingComma = true
    allowComments = true
    explicitNulls = false
}

/** Json for our own state files, pretty-printed so they stay readable when inspected by hand. */
internal val stateJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    prettyPrintIndent = "  "
    explicitNulls = false
}

internal fun parseJsonFile(path: Path): JsonElement = parseJsonText(String(path.readBytes(), Charsets.UTF_8))

/** Drops a byte order mark first, which Windows editors like to add. */
internal fun parseJsonText(text: String): JsonElement = lenientJson.parseToJsonElement(text.removePrefix(BOM))

private const val BOM = "\uFEFF"

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject

internal fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray) ?: emptyList()

internal fun JsonElement?.str(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> contentOrNull
    else -> null
}

/** Numbers sometimes come as strings ("5"), so both are accepted. */
internal fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }

internal fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.trim()?.toLongOrNull() }

internal fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.trim()?.toBooleanStrictOrNull() }

internal operator fun JsonElement?.get(key: String): JsonElement? = this.obj()?.get(key)
