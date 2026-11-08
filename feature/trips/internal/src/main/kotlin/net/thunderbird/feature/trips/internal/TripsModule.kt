package net.thunderbird.feature.trips.internal

import net.thunderbird.feature.trips.TravelMailDownloader
import net.thunderbird.feature.trips.TravelSenders
import net.thunderbird.feature.trips.TripsNavigation
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Binds the trips screen. [net.thunderbird.feature.trips.TravelMailSource] is bound by the app.
 */
val featureTripsModule = module {
    single<TripsNavigation> { DefaultTripsNavigation() }
    single { ReservationCache() }
    single { SharedPreferencesTravelSenderStore(context = androidContext()) } binds
        arrayOf(TravelSenderStore::class, TravelSenders::class)
    factory<TravelMailDownloader> { DefaultTravelMailDownloader(mailSource = get(), senders = get(), clock = get()) }
    viewModel { TripsViewModel(mailSource = get(), cache = get(), senders = get(), clock = get()) }
    viewModel { TravelSendersViewModel(senders = get()) }
}
