package net.thunderbird.core.preference.websiteicon

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.thunderbird.core.logging.Logger
import net.thunderbird.core.preference.PreferenceChangeBroker
import net.thunderbird.core.preference.PreferenceChangeSubscriber
import net.thunderbird.core.preference.PreferenceScope
import net.thunderbird.core.preference.storage.Storage
import net.thunderbird.core.preference.storage.StorageEditor
import net.thunderbird.core.preference.storage.StoragePersister

private const val TAG = "DefaultWebsiteIconSettingsPreferenceManager"

class DefaultWebsiteIconSettingsPreferenceManager(
    private val logger: Logger,
    private val storagePersister: StoragePersister,
    private val storageEditor: StorageEditor,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    preferenceChangeBroker: PreferenceChangeBroker,
) : WebsiteIconSettingsPreferenceManager, PreferenceChangeSubscriber {

    init {
        preferenceChangeBroker.subscribe(this)
    }

    private val configState: MutableStateFlow<WebsiteIconSettings> = MutableStateFlow(value = loadConfig())
    private val mutex = Mutex()
    private val storage: Storage
        get() = storagePersister.loadValues()

    override fun getConfig(): WebsiteIconSettings = configState.value
    override fun getConfigFlow(): Flow<WebsiteIconSettings> = configState

    override fun save(config: WebsiteIconSettings) {
        logger.debug(TAG) { "save() called with: config = $config" }
        writeConfig(config)
        configState.update { config }
    }

    private fun loadConfig(): WebsiteIconSettings = WebsiteIconSettings(
        isEnabled = storage.getBoolean(
            key = WebsiteIconSettingKey.Enabled.value,
            defValue = WEBSITE_ICON_SETTINGS_DEFAULT_IS_ENABLED,
        ),
    )

    private fun writeConfig(config: WebsiteIconSettings) {
        scope.launch(ioDispatcher) {
            mutex.withLock {
                storageEditor.putBoolean(WebsiteIconSettingKey.Enabled.value, config.isEnabled)
                storageEditor.commit().also { commited ->
                    logger.verbose(TAG) { "writeConfig: storageEditor.commit() resulted in: $commited" }
                }
            }
        }
    }

    override fun receive(scope: PreferenceScope) {
        if (scope == PreferenceScope.ALL || scope == PreferenceScope.WEBSITE_ICON) {
            configState.update { loadConfig() }
        }
    }
}
