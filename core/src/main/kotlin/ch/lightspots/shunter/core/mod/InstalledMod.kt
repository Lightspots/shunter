package ch.lightspots.shunter.core.mod

import java.nio.file.Path

/** Where a mod lives. Only [LOCAL] is managed (written) by us; the others belong to the game or the modder. */
enum class ModLocation(val label: String, val managed: Boolean) {
    /** `userdata/<id>/3493540/local/mods`: manually installed mods. */
    LOCAL("local", managed = true),

    /** `userdata/<id>/3493540/local/staging_area`: mods the user develops or uploads. */
    STAGING("staging", managed = false),

    /** mod.io / in-game Mod Hub subscriptions, managed by the game. Folder name is the mod.io id. */
    MOD_IO("mod.io", managed = false),
}

/** A mod folder found on disk. [manifest] is null when `mod.json` is missing or broken, see [problems]. */
data class InstalledMod(
    val location: ModLocation,
    val folder: Path,
    val manifest: ModManifest?,
    val metadata: ModMetadata?,
    val problems: List<String> = emptyList(),
) {
    val folderName: String get() = folder.fileName.toString()

    val modId: String? get() = manifest?.modId

    fun displayName(language: String? = null): String =
        metadata?.localized(language)?.name ?: manifest?.modId ?: folderName

    val previewImage: Path get() = folder.resolve(ModMetadata.DIR_NAME).resolve(ModMetadata.PREVIEW_FILE_NAME)
}
