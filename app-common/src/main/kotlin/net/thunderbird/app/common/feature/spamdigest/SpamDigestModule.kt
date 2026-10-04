package net.thunderbird.app.common.feature.spamdigest

import net.thunderbird.app.common.feature.impersonation.LegacyKnownSendersSource
import net.thunderbird.feature.impersonation.KnownSendersSource
import net.thunderbird.feature.impersonation.internal.featureImpersonationModule
import net.thunderbird.feature.spamdigest.SpamAlertNotifier
import net.thunderbird.feature.spamdigest.SpamDigestAccounts
import net.thunderbird.feature.spamdigest.SpamDigestMailer
import net.thunderbird.feature.spamdigest.SpamFolderBackgroundSync
import net.thunderbird.feature.spamdigest.SpamFolderReader
import net.thunderbird.feature.spamdigest.internal.featureSpamDigestModule
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

internal val appCommonFeatureSpamDigestModule = module {
    includes(featureSpamDigestModule)
    includes(featureImpersonationModule)

    factory<KnownSendersSource> {
        LegacyKnownSendersSource(
            accountManager = get(),
            recipientIndex = get(),
            messageStoreManager = get(),
        )
    }

    factory {
        SpamSenderAssessor(
            authenticationServerTrust = get(),
            knownCorrespondents = get(),
            knownContacts = get(),
            recipientIndex = get(),
            impersonationChecker = get(),
        )
    }

    single {
        SpamArrivalMessagingListener(
            messageStoreManager = get(),
            spamSenderAssessor = get(),
            spamArrivalListener = get(),
            clock = get(),
            logger = get(),
        )
    }

    factory<SpamAlertNotifier> {
        LegacySpamAlertNotifier(
            context = androidContext(),
            accountManager = get(),
            notificationHelper = get(),
            actionCreator = get(),
            resourceProvider = get(),
            logger = get(),
        )
    }

    factory<SpamFolderBackgroundSync> {
        LegacySpamFolderBackgroundSync(accountManager = get(), messageStoreManager = get())
    }

    factory<SpamDigestAccounts> {
        LegacySpamDigestAccounts(accountManager = get())
    }

    factory<SpamFolderReader> {
        LegacySpamFolderReader(
            accountManager = get(),
            messagingController = get(),
            messageStoreManager = get(),
            spamSenderAssessor = get(),
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
