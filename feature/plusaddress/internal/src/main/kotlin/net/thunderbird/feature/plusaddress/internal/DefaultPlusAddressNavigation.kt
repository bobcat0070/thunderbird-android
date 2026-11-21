package net.thunderbird.feature.plusaddress.internal

import androidx.navigation.NavGraphBuilder
import net.thunderbird.core.ui.navigation.deepLinkComposable
import net.thunderbird.feature.plusaddress.PlusAddressNavigation
import net.thunderbird.feature.plusaddress.PlusAddressRoute

internal class DefaultPlusAddressNavigation : PlusAddressNavigation {
    override fun registerRoutes(
        navGraphBuilder: NavGraphBuilder,
        onBack: () -> Unit,
        onFinish: (PlusAddressRoute) -> Unit,
    ) {
        with(navGraphBuilder) {
            deepLinkComposable<PlusAddressRoute.PlusAddresses>(PlusAddressRoute.PlusAddresses.BASE_PATH) {
                PlusAddressesScreen(onBack = onBack)
            }
        }
    }
}
