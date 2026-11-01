package net.thunderbird.feature.packages.internal

import androidx.navigation.NavGraphBuilder
import net.thunderbird.core.ui.navigation.deepLinkComposable
import net.thunderbird.feature.packages.PackagesNavigation
import net.thunderbird.feature.packages.PackagesRoute

internal class DefaultPackagesNavigation : PackagesNavigation {
    override fun registerRoutes(
        navGraphBuilder: NavGraphBuilder,
        onBack: () -> Unit,
        onFinish: (PackagesRoute) -> Unit,
    ) {
        with(navGraphBuilder) {
            deepLinkComposable<PackagesRoute.Packages>(PackagesRoute.Packages.BASE_PATH) {
                PackagesScreen(
                    onBack = onBack,
                    onOpenCarrierAccounts = { onFinish(PackagesRoute.CarrierAccounts) },
                )
            }
            deepLinkComposable<PackagesRoute.CarrierAccounts>(PackagesRoute.CarrierAccounts.BASE_PATH) {
                CarrierAccountsScreen(onBack = onBack)
            }
        }
    }
}
