package net.thunderbird.feature.packages.internal

import java.util.concurrent.TimeUnit
import net.thunderbird.feature.packages.PackagesNavigation
import net.thunderbird.feature.packages.ParcelMarkSettings
import net.thunderbird.feature.packages.ShippingMailDownloader
import net.thunderbird.feature.packages.internal.carrier.CarrierApiTracker
import net.thunderbird.feature.packages.internal.carrier.CarrierCredentialsStore
import net.thunderbird.feature.packages.internal.carrier.LiveTracker
import net.thunderbird.feature.packages.internal.carrier.SharedPreferencesCarrierCredentialsStore
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.binds
import org.koin.dsl.module

private const val CARRIER_CALL_TIMEOUT_SECONDS = 30L

/**
 * Binds the packages screens. [net.thunderbird.feature.packages.ShippingMailSource] is bound by the app.
 */
val featurePackagesModule = module {
    single<PackagesNavigation> { DefaultPackagesNavigation() }
    single<CarrierCredentialsStore> { SharedPreferencesCarrierCredentialsStore(context = androidContext()) }
    single { SharedPreferencesParcelMarks(context = androidContext(), clock = get()) } binds
        arrayOf(ParcelMarks::class, ParcelMarkSettings::class)
    single<LiveTracker> {
        CarrierApiTracker(
            httpClient = OkHttpClient.Builder()
                .callTimeout(CARRIER_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(false)
                .build(),
            credentialsStore = get(),
            clock = get(),
        )
    }
    factory<ShippingMailDownloader> { DefaultShippingMailDownloader(mailSource = get(), clock = get()) }
    viewModel { PackagesViewModel(mailSource = get(), marks = get(), liveTracker = get(), clock = get()) }
    viewModel { CarrierAccountsViewModel(credentialsStore = get()) }
}
