package net.thunderbird.feature.mailboxrules.internal

import androidx.navigation.NavGraphBuilder
import net.thunderbird.core.ui.navigation.deepLinkComposable
import net.thunderbird.feature.mailboxrules.MailboxRulesNavigation
import net.thunderbird.feature.mailboxrules.MailboxRulesRoute

internal class DefaultMailboxRulesNavigation : MailboxRulesNavigation {
    override fun registerRoutes(
        navGraphBuilder: NavGraphBuilder,
        onBack: () -> Unit,
        onFinish: (MailboxRulesRoute) -> Unit,
    ) {
        with(navGraphBuilder) {
            deepLinkComposable<MailboxRulesRoute.Rules>(MailboxRulesRoute.Rules.BASE_PATH) {
                MailboxRulesScreen(onBack = onBack)
            }
        }
    }
}
