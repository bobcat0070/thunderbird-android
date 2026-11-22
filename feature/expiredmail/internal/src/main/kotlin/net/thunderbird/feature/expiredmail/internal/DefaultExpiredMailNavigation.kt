package net.thunderbird.feature.expiredmail.internal

import androidx.navigation.NavGraphBuilder
import net.thunderbird.core.ui.navigation.deepLinkComposable
import net.thunderbird.feature.expiredmail.ExpiredMailNavigation
import net.thunderbird.feature.expiredmail.ExpiredMailRoute

internal class DefaultExpiredMailNavigation : ExpiredMailNavigation {
    override fun registerRoutes(
        navGraphBuilder: NavGraphBuilder,
        onBack: () -> Unit,
        onFinish: (ExpiredMailRoute) -> Unit,
    ) {
        with(navGraphBuilder) {
            deepLinkComposable<ExpiredMailRoute.Overview>(ExpiredMailRoute.Overview.BASE_PATH) {
                ExpiredMailScreen(onBack = onBack)
            }
        }
    }
}
