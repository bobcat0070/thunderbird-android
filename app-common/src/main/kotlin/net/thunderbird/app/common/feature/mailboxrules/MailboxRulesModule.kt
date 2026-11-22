package net.thunderbird.app.common.feature.mailboxrules

import net.thunderbird.feature.mailboxrules.MailboxRulesSource
import net.thunderbird.feature.mailboxrules.internal.featureMailboxRulesModule
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

internal val appCommonFeatureMailboxRulesModule = module {
    includes(featureMailboxRulesModule)

    // One for each screen, which closes it when it goes: it holds the sign-in's service while the screen is open.
    factory<MailboxRulesSource> {
        LegacyMailboxRulesSource(
            context = androidContext(),
            accountManager = get(),
            oAuthConfigurationProvider = get(),
        )
    }
}
