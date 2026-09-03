package net.thunderbird.app.common.feature

import app.k9mail.feature.launcher.FeatureLauncherExternalContract
import app.k9mail.feature.launcher.di.featureLauncherModule
import net.thunderbird.app.common.feature.mail.appCommonFeatureMailModule
import net.thunderbird.feature.account.avatar.di.featureAccountAvatarModule
import net.thunderbird.feature.mail.message.classification.api.ClassificationOverrideStore
import net.thunderbird.feature.mail.message.classification.internal.SharedPreferencesClassificationOverrideStore
import net.thunderbird.feature.mail.message.classification.internal.featureMessageClassificationModule
import net.thunderbird.feature.mail.message.composer.internal.featureMessageComposerModule
import net.thunderbird.feature.mail.message.reader.impl.inject.featureMessageReaderModule
import net.thunderbird.feature.navigation.drawer.api.NavigationDrawerExternalContract
import net.thunderbird.feature.notification.impl.inject.featureNotificationModule
import net.thunderbird.feature.thundermail.internal.common.inject.featureThundermailCommonModule
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

internal val appCommonFeatureModule = module {
    includes(appCommonFeatureMailModule)
    includes(featureAccountAvatarModule)
    includes(featureLauncherModule)
    includes(featureNotificationModule)
    includes(featureMessageClassificationModule)
    includes(featureMessageComposerModule)
    includes(featureMessageReaderModule)
    includes(featureThundermailCommonModule)

    single<ClassificationOverrideStore> {
        SharedPreferencesClassificationOverrideStore(
            context = androidContext(),
        )
    }

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
}
