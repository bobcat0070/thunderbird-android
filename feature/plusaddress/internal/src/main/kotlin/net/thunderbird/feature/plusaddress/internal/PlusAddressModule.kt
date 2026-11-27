package net.thunderbird.feature.plusaddress.internal

import net.thunderbird.feature.plusaddress.PlusAddressDecisions
import net.thunderbird.feature.plusaddress.PlusAddressLeakChecker
import net.thunderbird.feature.plusaddress.PlusAddressLookup
import net.thunderbird.feature.plusaddress.PlusAddressNavigation
import net.thunderbird.feature.plusaddress.PlusAddressSettings
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Binds the plus address leak check and its screen. [net.thunderbird.feature.plusaddress.PlusAddressMailSource] is
 * bound by the app.
 */
val featurePlusAddressModule = module {
    single<PlusAddressNavigation> { DefaultPlusAddressNavigation() }
    single { SharedPreferencesPlusAddressDecisions(context = androidContext()) } binds
        arrayOf(PlusAddressDecisionStore::class, PlusAddressDecisions::class, PlusAddressSettings::class)
    single {
        CachingPlusAddressLeakChecker(mailSource = get(), decisionStore = get(), clock = get())
    } binds arrayOf(PlusAddressLeakChecker::class, PlusAddressOverview::class, PlusAddressLookup::class)
    viewModel { PlusAddressesViewModel(overview = get(), decisions = get()) }
}
