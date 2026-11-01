package net.thunderbird.app.common.feature.packages

import net.thunderbird.feature.packages.ShippingMailSource
import net.thunderbird.feature.packages.internal.featurePackagesModule
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

internal val appCommonFeaturePackagesModule = module {
    includes(featurePackagesModule)

    single<ShippingMailSource> {
        LegacyShippingMailSource(
            context = androidContext(),
            accountManager = get(),
            messageStoreManager = get(),
            localStoreProvider = get(),
            messagingController = get(),
        )
    }
}
