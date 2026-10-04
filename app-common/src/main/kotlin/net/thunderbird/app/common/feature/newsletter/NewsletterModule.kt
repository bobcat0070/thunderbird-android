package net.thunderbird.app.common.feature.newsletter

import net.thunderbird.feature.newsletter.NewsletterActions
import net.thunderbird.feature.newsletter.NewsletterSenderRepository
import net.thunderbird.feature.newsletter.internal.featureNewsletterModule
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

internal val appCommonFeatureNewsletterModule = module {
    includes(featureNewsletterModule)

    factory<NewsletterSenderRepository> {
        LegacyNewsletterSenderRepository(
            accountManager = get(),
            messageStoreManager = get(),
            messageRepository = get(),
        )
    }

    factory<NewsletterActions> {
        LegacyNewsletterActions(
            context = androidContext(),
            accountManager = get(),
            messageStoreManager = get(),
            messageRepository = get(),
            messagingController = get(),
            oneClickUnsubscriber = get(),
        )
    }
}
