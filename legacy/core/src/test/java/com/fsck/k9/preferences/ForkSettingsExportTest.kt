package com.fsck.k9.preferences

import app.k9mail.legacy.di.DI.get
import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.fsck.k9.K9RobolectricTest
import com.fsck.k9.Preferences
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.jdom2.Document
import org.jdom2.input.SAXBuilder
import org.junit.Before
import org.junit.Test
import org.koin.core.component.inject
import org.mockito.kotlin.mock
import org.robolectric.RuntimeEnvironment

/**
 * The settings this fork added have to survive being exported and imported onto another device.
 */
class ForkSettingsExportTest : K9RobolectricTest() {
    private val preferences: Preferences by inject()
    private val folderSettingsProvider: FolderSettingsProvider by inject()
    private val externalSettings = FakeExternalGlobalSettings()

    private val testSubject by lazy {
        SettingsExporter(
            RuntimeEnvironment.getApplication().contentResolver,
            preferences,
            folderSettingsProvider,
            folderQueryRepository = get(),
            notificationSettingsUpdater = mock(),
            filePrefixProvider = mock(),
            externalGlobalSettings = listOf(externalSettings),
        )
    }

    private val importer by lazy {
        SettingsImporter(
            settingsFileParser = get(),
            generalSettingsValidator = get(),
            accountSettingsValidator = get(),
            generalSettingsUpgrader = get(),
            accountSettingsWriter = get(),
            accountSettingsUpgrader = get(),
            generalSettingsWriter = GeneralSettingsWriter(
                preferences = preferences,
                generalSettingsManager = get(),
                changePublisher = get(),
                externalGlobalSettings = listOf(externalSettings),
            ),
            unifiedInboxConfigurator = mock(),
        )
    }

    @Before
    fun before() {
        preferences.clearAccounts()
    }

    @Test
    fun `every fork setting should be exported with its stored value`() = runTest {
        // Every one set away from its default, so a value that is merely the default cannot pass by accident.
        writeStorage(NON_DEFAULT_VALUES)

        val global = exportGlobalSettings()

        for ((key, value) in NON_DEFAULT_VALUES) {
            assertThat(global[key], key).isEqualTo(value)
        }
    }

    @Test
    fun `the Gravatar API key should never be exported`() = runTest {
        // A credential, and an export never carries credentials - like server passwords.
        writeStorage(mapOf("gravatarApiKey" to "secret-key-value"))

        val global = exportGlobalSettings()

        assertThat(global["gravatarApiKey"]).isNull()
    }

    @Test
    fun `settings kept outside the main storage should be exported`() = runTest {
        externalSettings.values = mapOf(ExternalSettingKeys.LEARNED_CLASSIFICATION_RULES_KEY to "[\"rule\"]")

        val global = exportGlobalSettings()

        assertThat(global[ExternalSettingKeys.LEARNED_CLASSIFICATION_RULES_KEY]).isEqualTo("[\"rule\"]")
    }

    @Test
    fun `every fork setting should come back on import`() = runTest {
        writeStorage(NON_DEFAULT_VALUES)
        val exported = export()
        resetStorage(NON_DEFAULT_VALUES.keys)

        importer.importSettings(exported.inputStream(), globalSettings = true, accountUuids = emptyList())

        for ((key, value) in NON_DEFAULT_VALUES) {
            assertThat(preferences.storage.getStringOrNull(key), key).isEqualTo(value)
        }
    }

    @Test
    fun `settings kept outside the main storage should go back to their store, not the main storage`() = runTest {
        externalSettings.values = mapOf(ExternalSettingKeys.LEARNED_CLASSIFICATION_RULES_KEY to "[\"rule\"]")
        val exported = export()
        externalSettings.values = emptyMap()

        importer.importSettings(exported.inputStream(), globalSettings = true, accountUuids = emptyList())

        assertThat(externalSettings.imported)
            .containsOnly(ExternalSettingKeys.LEARNED_CLASSIFICATION_RULES_KEY to "[\"rule\"]")
        assertThat(preferences.storage.getStringOrNull(ExternalSettingKeys.LEARNED_CLASSIFICATION_RULES_KEY)).isNull()
    }

    @Test
    fun `an export from before these settings existed should reset them, like any newer setting`() = runTest {
        // Upstream's rule for every setting: importing global settings makes them match the file, and a setting
        // the file predates takes its default. The fork's settings follow it rather than behaving differently.
        writeStorage(mapOf("bimiEnabled" to "true"))

        importer.importSettings(OLD_FILE.inputStream(), globalSettings = true, accountUuids = emptyList())

        assertThat(preferences.storage.getStringOrNull("bimiEnabled")).isEqualTo("false")
    }

    @Test
    fun `an export from before these settings existed should leave learned lists alone`() = runTest {
        // The file has no value for them, and an import only ever adds to them.
        importer.importSettings(OLD_FILE.inputStream(), globalSettings = true, accountUuids = emptyList())

        assertThat(externalSettings.imported).isEmpty()
    }

    private fun writeStorage(values: Map<String, String>) {
        val editor = preferences.createStorageEditor()
        values.forEach { (key, value) -> editor.putString(key, value) }
        editor.commit()
    }

    private fun resetStorage(keys: Set<String>) {
        val editor = preferences.createStorageEditor()
        keys.forEach { editor.remove(it) }
        editor.commit()
    }

    private suspend fun export(): ByteArray {
        return ByteArrayOutputStream().use { outputStream ->
            testSubject.exportPreferences(outputStream, includeGlobals = true, emptySet(), includePasswords = false)
            outputStream.toByteArray()
        }
    }

    private suspend fun exportGlobalSettings(): Map<String, String> {
        val document: Document = SAXBuilder().build(export().inputStream())

        return document.rootElement.getChild("global").children.associate { element ->
            element.getAttributeValue("key") to element.text
        }
    }

    private class FakeExternalGlobalSettings : ExternalGlobalSettings {
        var values: Map<String, String> = emptyMap()
        val imported = mutableMapOf<String, String>()

        override val keys: Set<String> = setOf(
            ExternalSettingKeys.LEARNED_CLASSIFICATION_RULES_KEY,
            ExternalSettingKeys.REMOTE_IMAGE_TRUSTED_SENDERS_KEY,
            ExternalSettingKeys.REMOTE_IMAGE_TRUSTED_DOMAINS_KEY,
        )

        override fun exportSettings(): Map<String, String> = values

        override fun importSettings(values: Map<String, String>) {
            imported.putAll(values)
        }
    }

    private companion object {
        // A real export from before the fork's settings existed: it carries global settings of its own, which is
        // what makes an import apply global settings at all.
        val OLD_FILE = """<?xml version="1.0" encoding="UTF-8"?>
            <k9settings format="1" version="111"><global>
            <value key="drawerExpandAllFolder">false</value></global><accounts/></k9settings>
        """.trimIndent().toByteArray()

        /**
         * Each fork setting at a value other than its default.
         */
        val NON_DEFAULT_VALUES = mapOf(
            "gravatarEnabled" to "true",
            "bimiEnabled" to "true",
            "websiteIconEnabled" to "true",
            "directorySearchEnabled" to "true",
            "messageViewSenderAuthenticationVisible" to "true",
            "categoryGroupingEnabled" to "false",
            "notifyPersonal" to "false",
            "notifyNotifications" to "false",
            "notifyNewsletters" to "false",
            "widgetShowPersonal" to "false",
            "widgetShowNotifications" to "false",
            "widgetShowNewsletters" to "false",
        )
    }
}
