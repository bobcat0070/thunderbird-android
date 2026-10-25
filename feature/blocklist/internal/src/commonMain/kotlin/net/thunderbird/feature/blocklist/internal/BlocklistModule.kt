package net.thunderbird.feature.blocklist.internal

import net.thunderbird.feature.blocklist.DomainBlocklist
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Binds the blocklist lookup and its updater. [BlocklistSettingsProvider], [BlocklistStore] and [BlocklistFetcher]
 * are bound by the platform and the app.
 */
val featureBlocklistModule = module {
    single { DefaultDomainBlocklist(settingsProvider = get()) } bind DomainBlocklist::class
    single {
        DefaultBlocklistUpdater(
            blocklist = get(),
            settingsProvider = get(),
            store = get(),
            fetcher = get(),
            clock = get(),
            logger = get(),
        )
    }
}
