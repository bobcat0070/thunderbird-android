package net.thunderbird.feature.expiredmail.internal

import net.thunderbird.feature.expiredmail.ExpiredMailNavigation
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Binds the expired mail screen. [net.thunderbird.feature.expiredmail.ExpiredMailSource] is bound by the app.
 */
val featureExpiredMailModule = module {
    single<ExpiredMailNavigation> { DefaultExpiredMailNavigation() }
    viewModel { ExpiredMailViewModel(source = get()) }
}
