package net.thunderbird.feature.conversation.internal

import androidx.navigation.NavGraphBuilder
import androidx.navigation.toRoute
import net.thunderbird.core.ui.navigation.deepLinkComposable
import net.thunderbird.feature.conversation.ConversationNavigation
import net.thunderbird.feature.conversation.ConversationRoute

internal class DefaultConversationNavigation : ConversationNavigation {
    override fun registerRoutes(
        navGraphBuilder: NavGraphBuilder,
        onBack: () -> Unit,
        onFinish: (ConversationRoute) -> Unit,
    ) {
        with(navGraphBuilder) {
            deepLinkComposable<ConversationRoute.Thread>(ConversationRoute.Thread.BASE_PATH) { backStackEntry ->
                val route = backStackEntry.toRoute<ConversationRoute.Thread>()
                ConversationScreen(accountId = route.accountId, threadRoot = route.threadRoot, onBack = onBack)
            }
        }
    }
}
