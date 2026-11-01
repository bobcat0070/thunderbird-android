package net.thunderbird.feature.trips.internal

import net.thunderbird.feature.trips.TripsNavigation
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Binds the trips screen. [net.thunderbird.feature.trips.TravelMailSource] is bound by the app.
 */
val featureTripsModule = module {
    single<TripsNavigation> { DefaultTripsNavigation() }
    single { ReservationCache() }
    viewModel { TripsViewModel(mailSource = get(), cache = get(), clock = get()) }
}
