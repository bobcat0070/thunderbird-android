package net.thunderbird.core.preference.blocklist

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
import net.thunderbird.components.core.logging.Logger
import net.thunderbird.core.preference.PreferenceChangeBroker
import net.thunderbird.core.preference.PreferenceChangeSubscriber
import net.thunderbird.core.preference.PreferenceScope
import net.thunderbird.core.preference.storage.Storage
import net.thunderbird.core.preference.storage.StorageProvider
import net.thunderbird.core.preference.storage.getEnumOrDefault
import net.thunderbird.core.preference.storage.putEnum

private const val TAG = "DefaultDomainBlocklistSettingsPreferenceManager"

class DefaultDomainBlocklistSettingsPreferenceManager(
    private val logger: Logger,
    private val storageProvider: StorageProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    preferenceChangeBroker: PreferenceChangeBroker,
) : DomainBlocklistSettingsPreferenceManager, PreferenceChangeSubscriber {

    init {
        preferenceChangeBroker.subscribe(this)
    }

    private val configState: MutableStateFlow<DomainBlocklistSettings> = MutableStateFlow(value = loadConfig())
    private val mutex = Mutex()
    private val storage: Storage
        get() = storageProvider.storage

    override fun getConfig(): DomainBlocklistSettings = configState.value
    override fun getConfigFlow(): Flow<DomainBlocklistSettings> = configState

    override fun save(config: DomainBlocklistSettings) {
        logger.debug(TAG) { "save() called with: config = $config" }
        writeConfig(config)
        configState.update { config }
    }

    private fun loadConfig(): DomainBlocklistSettings = DomainBlocklistSettings(
        tier = storage.getEnumOrDefault(
            key = DomainBlocklistSettingKey.Tier.value,
            default = DOMAIN_BLOCKLIST_SETTINGS_DEFAULT_TIER,
        ),
        isThreatListEnabled = storage.getBoolean(
            key = DomainBlocklistSettingKey.ThreatListEnabled.value,
            defValue = DOMAIN_BLOCKLIST_SETTINGS_DEFAULT_IS_THREAT_LIST_ENABLED,
        ),
    )

    private fun writeConfig(config: DomainBlocklistSettings) {
        scope.launch(ioDispatcher) {
            mutex.withLock {
                val storageEditor = storageProvider.createStorageEditor()
                storageEditor.putEnum(DomainBlocklistSettingKey.Tier.value, config.tier)
                storageEditor.putBoolean(DomainBlocklistSettingKey.ThreatListEnabled.value, config.isThreatListEnabled)
                storageEditor.commit().also { committed ->
                    logger.verbose(TAG) { "writeConfig: storageEditor.commit() resulted in: $committed" }
                }
            }
        }
    }

    override fun receive(scope: PreferenceScope) {
        if (scope == PreferenceScope.ALL || scope == PreferenceScope.DOMAIN_BLOCKLIST) {
            configState.update { loadConfig() }
        }
    }
}
