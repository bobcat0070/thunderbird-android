package net.thunderbird.app.common.feature.spamdigest

import net.thunderbird.feature.spamdigest.SpamDigestAccounts
import net.thunderbird.feature.spamdigest.SpamDigestMailer
import net.thunderbird.feature.spamdigest.SpamFolderReader
import net.thunderbird.feature.spamdigest.internal.featureSpamDigestModule
import org.koin.dsl.module

internal val appCommonFeatureSpamDigestModule = module {
    includes(featureSpamDigestModule)

    factory<SpamDigestAccounts> {
        LegacySpamDigestAccounts(accountManager = get())
    }

    factory<SpamFolderReader> {
        LegacySpamFolderReader(
            accountManager = get(),
            messagingController = get(),
            messageStoreManager = get(),
            authenticationServerTrust = get(),
        )
    }

    factory<SpamDigestMailer> {
        LegacySpamDigestMailer(
            accountManager = get(),
            messagingController = get(),
            generalSettingsManager = get(),
        )
    }
}
