package net.thunderbird.core.preference.widget

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

private const val TAG = "DefaultWidgetSettingsPreferenceManager"

class DefaultWidgetSettingsPreferenceManager(
    private val logger: Logger,
    private val storagePersister: StoragePersister,
    private val storageEditor: StorageEditor,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    preferenceChangeBroker: PreferenceChangeBroker,
) : WidgetSettingsPreferenceManager, PreferenceChangeSubscriber {

    init {
        preferenceChangeBroker.subscribe(this)
    }

    private val configState: MutableStateFlow<WidgetSettings> = MutableStateFlow(value = loadConfig())
    private val mutex = Mutex()
    private val storage: Storage
        get() = storagePersister.loadValues()

    override fun getConfig(): WidgetSettings = configState.value
    override fun getConfigFlow(): Flow<WidgetSettings> = configState

    override fun save(config: WidgetSettings) {
        logger.debug(TAG) { "save() called with: config = $config" }
        writeConfig(config)
        configState.update { config }
    }

    private fun loadConfig(): WidgetSettings = WidgetSettings(
        showPersonal = storage.getBoolean(
            key = WidgetSettingKey.ShowPersonal.value,
            defValue = WIDGET_SETTINGS_DEFAULT_SHOW_PERSONAL,
        ),
        showNotifications = storage.getBoolean(
            key = WidgetSettingKey.ShowNotifications.value,
            defValue = WIDGET_SETTINGS_DEFAULT_SHOW_NOTIFICATIONS,
        ),
        showNewsletters = storage.getBoolean(
            key = WidgetSettingKey.ShowNewsletters.value,
            defValue = WIDGET_SETTINGS_DEFAULT_SHOW_NEWSLETTERS,
        ),
    )

    private fun writeConfig(config: WidgetSettings) {
        scope.launch(ioDispatcher) {
            mutex.withLock {
                storageEditor.putBoolean(WidgetSettingKey.ShowPersonal.value, config.showPersonal)
                storageEditor.putBoolean(WidgetSettingKey.ShowNotifications.value, config.showNotifications)
                storageEditor.putBoolean(WidgetSettingKey.ShowNewsletters.value, config.showNewsletters)
                storageEditor.commit().also { commited ->
                    logger.verbose(TAG) { "writeConfig: storageEditor.commit() resulted in: $commited" }
                }
            }
        }
    }

    override fun receive(scope: PreferenceScope) {
        if (scope == PreferenceScope.ALL || scope == PreferenceScope.WIDGET) {
            configState.update { loadConfig() }
        }
    }
}
