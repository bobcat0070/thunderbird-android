package net.thunderbird.feature.newsletter.internal

import net.thunderbird.feature.newsletter.NewsletterNavigation
import net.thunderbird.feature.newsletter.UnsubscribeFollowUp
import net.thunderbird.feature.newsletter.UnsubscribeHistory
import net.thunderbird.feature.newsletter.UnsubscribeHistorySettings
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Binds the newsletter screen and the unsubscribe record. [net.thunderbird.feature.newsletter.NewsletterSenderRepository]
 * and [net.thunderbird.feature.newsletter.NewsletterActions] are bound by the app.
 */
val featureNewsletterModule = module {
    single<NewsletterNavigation> { DefaultNewsletterNavigation() }
    viewModel { NewsletterSendersViewModel(repository = get(), actions = get()) }

    single { SharedPreferencesUnsubscribeHistory(context = androidContext()) } binds arrayOf(
        UnsubscribeStore::class,
        UnsubscribeHistory::class,
        UnsubscribeHistorySettings::class,
    )
    single<UnsubscribeFollowUp> { StoreUnsubscribeFollowUp(store = get()) }
}
