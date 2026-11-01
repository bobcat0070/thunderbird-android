package net.thunderbird.feature.attachments.internal

import androidx.navigation.NavGraphBuilder
import androidx.navigation.toRoute
import net.thunderbird.core.ui.navigation.deepLinkComposable
import net.thunderbird.feature.attachments.AttachmentsNavigation
import net.thunderbird.feature.attachments.AttachmentsRoute

internal class DefaultAttachmentsNavigation : AttachmentsNavigation {
    override fun registerRoutes(
        navGraphBuilder: NavGraphBuilder,
        onBack: () -> Unit,
        onFinish: (AttachmentsRoute) -> Unit,
    ) {
        with(navGraphBuilder) {
            deepLinkComposable<AttachmentsRoute.Sender>(AttachmentsRoute.Sender.BASE_PATH) { backStackEntry ->
                val route = backStackEntry.toRoute<AttachmentsRoute.Sender>()
                SenderAttachmentsScreen(address = route.address, onBack = onBack)
            }
        }
    }
}
