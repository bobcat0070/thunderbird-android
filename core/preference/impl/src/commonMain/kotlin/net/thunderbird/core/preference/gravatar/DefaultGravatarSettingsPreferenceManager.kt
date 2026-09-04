package net.thunderbird.core.preference.gravatar

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

private const val TAG = "DefaultGravatarSettingsPreferenceManager"

class DefaultGravatarSettingsPreferenceManager(
    private val logger: Logger,
    private val storagePersister: StoragePersister,
    private val storageEditor: StorageEditor,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    preferenceChangeBroker: PreferenceChangeBroker,
) : GravatarSettingsPreferenceManager, PreferenceChangeSubscriber {

    init {
        preferenceChangeBroker.subscribe(this)
    }

    private val configState: MutableStateFlow<GravatarSettings> = MutableStateFlow(value = loadConfig())
    private val mutex = Mutex()
    private val storage: Storage
        get() = storagePersister.loadValues()

    override fun getConfig(): GravatarSettings = configState.value
    override fun getConfigFlow(): Flow<GravatarSettings> = configState

    override fun save(config: GravatarSettings) {
        // The key is deliberately not logged.
        logger.debug(TAG) { "save() called with: isEnabled = ${config.isEnabled}" }
        writeConfig(config)
        configState.update { config }
    }

    private fun loadConfig(): GravatarSettings = GravatarSettings(
        isEnabled = storage.getBoolean(
            key = GravatarSettingKey.Enabled.value,
            defValue = GRAVATAR_SETTINGS_DEFAULT_IS_ENABLED,
        ),
        apiKey = storage.getStringOrDefault(
            key = GravatarSettingKey.ApiKey.value,
            defValue = GRAVATAR_SETTINGS_DEFAULT_API_KEY,
        ),
    )

    private fun writeConfig(config: GravatarSettings) {
        scope.launch(ioDispatcher) {
            mutex.withLock {
                storageEditor.putBoolean(GravatarSettingKey.Enabled.value, config.isEnabled)
                storageEditor.putString(GravatarSettingKey.ApiKey.value, config.apiKey)
                storageEditor.commit().also { commited ->
                    logger.verbose(TAG) { "writeConfig: storageEditor.commit() resulted in: $commited" }
                }
            }
        }
    }

    override fun receive(scope: PreferenceScope) {
        if (scope == PreferenceScope.ALL || scope == PreferenceScope.GRAVATAR) {
            configState.update { loadConfig() }
        }
    }
}
