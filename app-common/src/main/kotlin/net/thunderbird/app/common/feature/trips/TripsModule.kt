package net.thunderbird.app.common.feature.trips

import net.thunderbird.feature.trips.TravelMailSource
import net.thunderbird.feature.trips.internal.featureTripsModule
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

internal val appCommonFeatureTripsModule = module {
    includes(featureTripsModule)

    factory<TravelMailSource> {
        LegacyTravelMailSource(
            context = androidContext(),
            accountManager = get(),
            messageStoreManager = get(),
            localStoreProvider = get(),
            messagingController = get(),
        )
    }
}
