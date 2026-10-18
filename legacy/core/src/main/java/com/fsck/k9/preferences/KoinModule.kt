package com.fsck.k9.preferences

import kotlin.time.ExperimentalTime
import net.thunderbird.core.preference.DefaultPreferenceChangeBroker
import net.thunderbird.core.preference.GeneralSettingsManager
import net.thunderbird.core.preference.PreferenceChangeBroker
import net.thunderbird.core.preference.PreferenceChangePublisher
import net.thunderbird.core.preference.debugging.DebuggingSettingsPreferenceManager
import net.thunderbird.core.preference.debugging.DefaultDebuggingSettingsPreferenceManager
import net.thunderbird.core.preference.display.DefaultDisplaySettingsPreferenceManager
import net.thunderbird.core.preference.display.DisplaySettingsPreferenceManager
import net.thunderbird.core.preference.display.coreSettings.DefaultDisplayCoreSettingsPreferenceManager
import net.thunderbird.core.preference.display.coreSettings.DisplayCoreSettingsPreferenceManager
import net.thunderbird.core.preference.display.inboxSettings.DefaultDisplayInboxSettingsPreferenceManager
import net.thunderbird.core.preference.display.inboxSettings.DisplayInboxSettingsPreferenceManager
import net.thunderbird.core.preference.display.miscSettings.DefaultDisplayMiscSettingsPreferenceManager
import net.thunderbird.core.preference.display.miscSettings.DisplayMiscSettingsPreferenceManager
import net.thunderbird.core.preference.display.visualSettings.DefaultDisplayVisualSettingsPreferenceManager
import net.thunderbird.core.preference.display.visualSettings.DisplayVisualSettingsPreferenceManager
import net.thunderbird.core.preference.display.visualSettings.message.list.DefaultMessageListPreferencesManager
import net.thunderbird.core.preference.display.visualSettings.message.list.MessageListPreferencesManager
import net.thunderbird.core.preference.interaction.DefaultInteractionSettingsPreferenceManager
import net.thunderbird.core.preference.interaction.InteractionSettingsPreferenceManager
import net.thunderbird.core.preference.network.DefaultNetworkSettingsPreferenceManager
import net.thunderbird.core.preference.network.NetworkSettingsPreferenceManager
import net.thunderbird.core.preference.notification.DefaultNotificationPreferenceManager
import net.thunderbird.core.preference.notification.NotificationPreferenceManager
import net.thunderbird.core.preference.bimi.BimiSettingsPreferenceManager
import net.thunderbird.core.preference.directory.DefaultDirectorySearchSettingsPreferenceManager
import net.thunderbird.core.preference.directory.DirectorySearchSettingsPreferenceManager
import net.thunderbird.core.preference.websiteicon.DefaultWebsiteIconSettingsPreferenceManager
import net.thunderbird.core.preference.websiteicon.WebsiteIconSettingsPreferenceManager
import net.thunderbird.core.preference.bimi.DefaultBimiSettingsPreferenceManager
import net.thunderbird.core.preference.gravatar.DefaultGravatarSettingsPreferenceManager
import net.thunderbird.core.preference.gravatar.GravatarSettingsPreferenceManager
import net.thunderbird.core.preference.privacy.DefaultPrivacySettingsPreferenceManager
import net.thunderbird.core.preference.widget.DefaultWidgetSettingsPreferenceManager
import net.thunderbird.core.preference.widget.WidgetSettingsPreferenceManager
import net.thunderbird.core.preference.privacy.PrivacySettingsPreferenceManager
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

val preferencesModule = module {
    factory {
        SettingsExporter(
            contentResolver = get(),
            accountManager = get(),
            storageProvider = get(),
            folderSettingsProvider = get(),
            folderQueryRepository = get(),
            notificationSettingsUpdater = get(),
            filePrefixProvider = get(),
            externalGlobalSettings = getAll(),
        )
    }
    factory {
        FolderSettingsProvider(
            remoteFolderDetailsRepository = get(),
            folderPinSettings = getOrNull(),
            categoryGroupingStore = get(),
        )
    }
    single<CategoryGroupingStore> { PreferencesCategoryGroupingStore(storageProvider = get()) }
    // Taught classification rules travel with the other settings; see ExternalGlobalSettings. Named, because
    // several definitions bind that type and Koin refuses a second unnamed one at startup; getAll() still finds
    // every one of them.
    factory(named("classificationRulesExternalSettings")) {
        ClassificationRulesExternalSettings(store = get())
    } bind ExternalGlobalSettings::class
    single<GravatarSettingsPreferenceManager> {
        DefaultGravatarSettingsPreferenceManager(
            logger = get(),
            storageProvider = get(),
            preferenceChangeBroker = get(),
        )
    }
    single<BimiSettingsPreferenceManager> {
        DefaultBimiSettingsPreferenceManager(
            logger = get(),
            storageProvider = get(),
            preferenceChangeBroker = get(),
        )
    }
    single<DirectorySearchSettingsPreferenceManager> {
        DefaultDirectorySearchSettingsPreferenceManager(
            logger = get(),
            storageProvider = get(),
            preferenceChangeBroker = get(),
        )
    }
    single<WebsiteIconSettingsPreferenceManager> {
        DefaultWebsiteIconSettingsPreferenceManager(
            logger = get(),
            storageProvider = get(),
            preferenceChangeBroker = get(),
        )
    }
    single<WidgetSettingsPreferenceManager> {
        DefaultWidgetSettingsPreferenceManager(
            logger = get(),
            storageProvider = get(),
            preferenceChangeBroker = get(),
        )
    }
    single<PrivacySettingsPreferenceManager> {
        DefaultPrivacySettingsPreferenceManager(
            logger = get(),
            storageProvider = get(),
            preferenceChangeBroker = get(),
        )
    }
    single<NotificationPreferenceManager> {
        DefaultNotificationPreferenceManager(
            logger = get(),
            storageProvider = get(),
            preferenceChangeBroker = get(),
        )
    }
    single<DisplayCoreSettingsPreferenceManager> {
        DefaultDisplayCoreSettingsPreferenceManager(
            logger = get(),
            storageProvider = get(),
            preferenceChangeBroker = get(),
        )
    }
    single<DisplayInboxSettingsPreferenceManager> {
        DefaultDisplayInboxSettingsPreferenceManager(
            logger = get(),
            storageProvider = get(),
            preferenceChangeBroker = get(),
        )
    }
    single<DisplayVisualSettingsPreferenceManager> {
        DefaultDisplayVisualSettingsPreferenceManager(
            logger = get(),
            storageProvider = get(),
            messageListPreferences = get(),
            preferenceChangeBroker = get(),
        )
    }
    single<DisplayMiscSettingsPreferenceManager> {
        DefaultDisplayMiscSettingsPreferenceManager(
            logger = get(),
            storageProvider = get(),
            preferenceChangeBroker = get(),
        )
    }
    single<InteractionSettingsPreferenceManager> {
        DefaultInteractionSettingsPreferenceManager(
            logger = get(),
            storageProvider = get(),
            preferenceChangeBroker = get(),
        )
    }
    single<DisplaySettingsPreferenceManager> {
        DefaultDisplaySettingsPreferenceManager(
            logger = get(),
            coreSettingsPreferenceManager = get(),
            inboxSettingsPreferenceManager = get(),
            visualSettingsPreferenceManager = get(),
            miscSettingsPreferenceManager = get(),
        )
    }
    single<MessageListPreferencesManager> {
        DefaultMessageListPreferencesManager(
            logger = get(),
            storageProvider = get(),
            preferenceChangeBroker = get(),
        )
    }
    single<NetworkSettingsPreferenceManager> {
        DefaultNetworkSettingsPreferenceManager(
            logger = get(),
            storageProvider = get(),
            preferenceChangeBroker = get(),
        )
    }
    single<DebuggingSettingsPreferenceManager> {
        DefaultDebuggingSettingsPreferenceManager(
            logger = get(),
            storageProvider = get(),
            loggingControl = get(),
            debugLogConfigurator = get(),
            platformConfigProvider = get(),
            preferenceChangeBroker = get(),
        )
    }
    single {
        DefaultGeneralSettingsManager(
            storageProvider = get(),
            coroutineScope = get(named("AppCoroutineScope")),
            changePublisher = get(),
            privacySettingsPreferenceManager = get(),
            notificationPreferenceManager = get(),
            displaySettingsSettingsPreferenceManager = get(),
            displayCoreSettingsPreferenceManager = get(),
            displayInboxSettingsPreferenceManager = get(),
            displayVisualSettingsPreferenceManager = get(),
            messageListPreferencesManager = get(),
            displayMiscSettingsPreferenceManager = get(),
            networkSettingsPreferenceManager = get(),
            debuggingSettingsPreferenceManager = get(),
            interactionSettingsPreferenceManager = get(),
            gravatarSettingsPreferenceManager = get(),
            widgetSettingsPreferenceManager = get(),
            bimiSettingsPreferenceManager = get(),
            websiteIconSettingsPreferenceManager = get(),
            directorySearchSettingsPreferenceManager = get(),
            platformConfigProvider = get(),
            logger = get(),
        )
    } bind GeneralSettingsManager::class
    single {
        DefaultDrawerConfigManager(
            coroutineScope = get(named("AppCoroutineScope")),
            displayInboxSettingsPreferenceManager = get(),
            displayVisualSettingsPreferenceManager = get(),
        )
    } bind DrawerConfigManager::class

    factory { SettingsFileParser() }

    factory { GeneralSettingsValidator() }
    factory { GeneralSettingsUpgrader() }
    factory {
        GeneralSettingsWriter(
            storageProvider = get(),
            generalSettingsManager = get(),
            changePublisher = get(),
            externalGlobalSettings = getAll(),
        )
    }

    factory { AccountSettingsValidator() }

    factory { IdentitySettingsUpgrader(generalSettingsManager = get()) }
    factory { FolderSettingsUpgrader(generalSettingsManager = get()) }
    factory { ServerSettingsUpgrader(generalSettingsManager = get()) }

    factory {
        AccountSettingsUpgrader(
            identitySettingsUpgrader = get(),
            folderSettingsUpgrader = get(),
            serverSettingsUpgrader = get(),
            generalSettingsManager = get(),
        )
    }

    factory {
        @OptIn(ExperimentalTime::class)
        AccountSettingsWriter(
            accountManager = get(),
            storageProvider = get(),
            localFoldersCreator = get(),
            clock = get(),
            serverSettingsDtoSerializer = get(),
            context = get(),
            generalSettingsManager = get(),
        )
    }

    factory { UnifiedInboxConfigurator(accountManager = get(), generalSettingsManager = get()) }

    factory {
        SettingsImporter(
            settingsFileParser = get(),
            generalSettingsValidator = get(),
            accountSettingsValidator = get(),
            generalSettingsUpgrader = get(),
            accountSettingsUpgrader = get(),
            generalSettingsWriter = get(),
            accountSettingsWriter = get(),
            unifiedInboxConfigurator = get(),
        )
    }

    single { DefaultPreferenceChangeBroker() }
        .binds(
            arrayOf(
                PreferenceChangePublisher::class,
                PreferenceChangeBroker::class,
            ),
        )
}
