package com.fsck.k9.preferences

/**
 * Settings the user made that are kept outside the main preference storage, but still belong in a settings export.
 *
 * Some features keep their state in stores of their own - taught classification rules, senders whose remote images
 * load - because that state is a list rather than a single value. Export reads the main storage only, so without
 * this they would be silently left behind when moving to a new device.
 *
 * Each key must also be described in [GeneralSettingsDescriptions] as a string setting, which is what gives it a
 * version and lets older and newer app versions agree on whether to read it.
 */
interface ExternalGlobalSettings {
    /**
     * The global setting keys this instance owns.
     */
    val keys: Set<String>

    /**
     * @return the current value of each of [keys], in the form written to an export file.
     */
    fun exportSettings(): Map<String, String>

    /**
     * Adds what an import file holds to what is already here.
     *
     * Merged rather than replaced, because these are collections the user built up one decision at a time: importing
     * settings onto a device should not throw away the decisions already made on it.
     *
     * @param values imported values for some or all of [keys]. A value that cannot be read is ignored.
     */
    fun importSettings(values: Map<String, String>)
}

/**
 * Folders pinned for quick access, as a per-folder setting that can travel through export.
 *
 * Pins are stored by folder id, which is only meaningful in one device's database. Export therefore records them
 * against the folder's server id, like every other folder setting, and import applies them once that folder exists
 * on the new device.
 */
interface FolderPinSettings {
    /**
     * Whether the folder is offered as a one-tap target for filing mail.
     */
    fun isPinnedForFiling(accountUuid: String, folderId: Long): Boolean

    /**
     * Whether the folder is listed in the drawer's unified section.
     */
    fun isPinnedToDrawer(accountUuid: String, folderId: Long): Boolean

    /**
     * Pins the folder wherever the flags say, leaving any existing pin in place.
     */
    fun pin(accountUuid: String, folderId: Long, forFiling: Boolean, toDrawer: Boolean)
}

/**
 * The global setting keys whose values live in stores outside the main preference storage.
 */
object ExternalSettingKeys {
    const val LEARNED_CLASSIFICATION_RULES_KEY = "learnedClassificationRules"
    const val REMOTE_IMAGE_TRUSTED_SENDERS_KEY = "remoteImageTrustedSenders"
    const val REMOTE_IMAGE_TRUSTED_DOMAINS_KEY = "remoteImageTrustedDomains"
}
