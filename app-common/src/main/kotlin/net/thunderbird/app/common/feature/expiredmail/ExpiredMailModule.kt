package net.thunderbird.app.common.feature.expiredmail

import net.thunderbird.feature.expiredmail.ExpiredMailSource
import net.thunderbird.feature.expiredmail.internal.featureExpiredMailModule
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

internal val appCommonFeatureExpiredMailModule = module {
    includes(featureExpiredMailModule)

    factory<ExpiredMailSource> {
        LegacyExpiredMailSource(
            context = androidContext(),
            accountManager = get(),
            messageStoreManager = get(),
            messagingController = get(),
            deliveredParcelMail = get(),
            clock = get(),
        )
    }
}
