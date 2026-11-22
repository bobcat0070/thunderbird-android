package net.thunderbird.feature.mailboxrules.internal

import net.thunderbird.feature.mailboxrules.MailboxRulesNavigation
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Binds the mailbox rules screen. [net.thunderbird.feature.mailboxrules.MailboxRulesSource] is bound by the app.
 */
val featureMailboxRulesModule = module {
    single<MailboxRulesNavigation> { DefaultMailboxRulesNavigation() }
    viewModel { MailboxRulesViewModel(source = get()) }
}
