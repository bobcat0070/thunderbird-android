package net.thunderbird.feature.newsletter.internal

import androidx.navigation.NavGraphBuilder
import net.thunderbird.core.ui.navigation.deepLinkComposable
import net.thunderbird.feature.newsletter.NewsletterNavigation
import net.thunderbird.feature.newsletter.NewsletterRoute

internal class DefaultNewsletterNavigation : NewsletterNavigation {
    override fun registerRoutes(
        navGraphBuilder: NavGraphBuilder,
        onBack: () -> Unit,
        onFinish: (NewsletterRoute) -> Unit,
    ) {
        with(navGraphBuilder) {
            deepLinkComposable<NewsletterRoute.Senders>(NewsletterRoute.Senders.BASE_PATH) {
                NewsletterSendersScreen(onBack = onBack)
            }
        }
    }
}
