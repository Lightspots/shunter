package ch.lightspots.shunter.core.modio

import ch.lightspots.shunter.core.mod.InstalledMod

/**
 * The user's mod.io subscriptions next to the game's mod.io folder ([installed]), matched by folder
 * name, which is the mod.io id. The game's Mod Hub does the downloading; we only compare.
 */
class ModIoSubscriptions(val subscribed: List<ModIoMod>, val installed: List<InstalledMod>) {
    private val subscribedByFolder = subscribed.associateBy { it.folderName }
    private val installedFolders = installed.map { it.folderName }.toSet()

    /** The subscription for a mod in the mod.io folder; null when it is not subscribed. */
    fun subscriptionOf(mod: InstalledMod): ModIoMod? = subscribedByFolder[mod.folderName]

    /** Subscribed but not in the folder yet. The game downloads them when it starts. */
    val notDownloaded: List<ModIoMod> get() = subscribed.filter { it.folderName !in installedFolders }

    /** In the folder without a subscription, for example unsubscribed on the website. */
    val notSubscribed: List<InstalledMod> get() = installed.filter { it.folderName !in subscribedByFolder }
}
