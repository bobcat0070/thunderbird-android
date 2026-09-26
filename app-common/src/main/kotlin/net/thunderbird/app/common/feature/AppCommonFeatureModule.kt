package net.thunderbird.app.common.feature

import app.k9mail.feature.launcher.FeatureLauncherExternalContract
import app.k9mail.feature.launcher.di.featureLauncherModule
import com.fsck.k9.preferences.ExternalGlobalSettings
import com.fsck.k9.preferences.FolderPinSettings
import net.thunderbird.app.common.feature.mail.appCommonFeatureMailModule
import net.thunderbird.app.common.feature.settings.DefaultFolderPinSettings
import net.thunderbird.app.common.feature.settings.RemoteImageSendersExternalSettings
import net.thunderbird.feature.account.avatar.di.featureAccountAvatarModule
import net.thunderbird.feature.mail.message.composer.internal.featureMessageComposerModule
import net.thunderbird.feature.mail.message.reader.impl.inject.featureMessageReaderModule
import net.thunderbird.feature.navigation.drawer.api.NavigationDrawerExternalContract
import net.thunderbird.feature.notification.impl.inject.featureNotificationModule
import net.thunderbird.feature.thundermail.internal.common.inject.featureThundermailCommonModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

internal val appCommonFeatureModule = module {
    includes(appCommonFeatureMailModule)
    includes(featureAccountAvatarModule)
    includes(featureLauncherModule)
    includes(featureNotificationModule)
    includes(featureMessageComposerModule)
    includes(featureMessageReaderModule)
    includes(featureThundermailCommonModule)

    factory<FeatureLauncherExternalContract.MessageListLauncher> {
        MessageListLauncher(
            context = androidContext(),
        )
    }

    single<NavigationDrawerExternalContract.DrawerConfigLoader> {
        NavigationDrawerConfigLoader(get())
    }

    single<NavigationDrawerExternalContract.DrawerConfigWriter> {
        NavigationDrawerConfigWriter(get())
    }

    // Settings this fork keeps outside the main storage, so that settings export carries them too. Named, because
    // several definitions bind ExternalGlobalSettings and Koin refuses a second unnamed one at startup.
    factory(named("remoteImageSendersExternalSettings")) {
        RemoteImageSendersExternalSettings(store = get())
    } bind ExternalGlobalSettings::class
    factory<FolderPinSettings> {
        DefaultFolderPinSettings(
            pinnedFolderStore = get(),
            pinnedFolderRepository = get(),
        )
    }
}
