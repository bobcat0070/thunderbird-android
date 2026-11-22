package net.thunderbird.app.common.feature.newsletter

import com.fsck.k9.preferences.ExternalGlobalSettings
import net.thunderbird.app.common.feature.settings.UnsubscribeExternalSettings
import net.thunderbird.feature.newsletter.NewsletterActions
import net.thunderbird.feature.newsletter.NewsletterSenderRepository
import net.thunderbird.feature.newsletter.internal.featureNewsletterModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

internal val appCommonFeatureNewsletterModule = module {
    includes(featureNewsletterModule)

    factory<NewsletterSenderRepository> {
        LegacyNewsletterSenderRepository(
            accountManager = get(),
            messageStoreManager = get(),
            messageRepository = get(),
            clock = get(),
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
            unsubscribeHistory = get(),
            clock = get(),
        )
    }

    // Named, because several definitions bind ExternalGlobalSettings and Koin refuses a second unnamed one.
    factory(named("unsubscribeExternalSettings")) {
        UnsubscribeExternalSettings(settings = get())
    } bind ExternalGlobalSettings::class
}
