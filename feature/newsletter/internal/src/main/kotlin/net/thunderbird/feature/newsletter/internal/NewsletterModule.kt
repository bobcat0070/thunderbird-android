package net.thunderbird.feature.newsletter.internal

import net.thunderbird.feature.newsletter.NewsletterNavigation
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Binds the newsletter screen. [net.thunderbird.feature.newsletter.NewsletterSenderRepository] and
 * [net.thunderbird.feature.newsletter.NewsletterActions] are bound by the app.
 */
val featureNewsletterModule = module {
    single<NewsletterNavigation> { DefaultNewsletterNavigation() }
    viewModel { NewsletterSendersViewModel(repository = get(), actions = get()) }
}
