package net.thunderbird.feature.trips.internal

import androidx.navigation.NavGraphBuilder
import net.thunderbird.core.ui.navigation.deepLinkComposable
import net.thunderbird.feature.trips.TripsNavigation
import net.thunderbird.feature.trips.TripsRoute

internal class DefaultTripsNavigation : TripsNavigation {
    override fun registerRoutes(
        navGraphBuilder: NavGraphBuilder,
        onBack: () -> Unit,
        onFinish: (TripsRoute) -> Unit,
    ) {
        with(navGraphBuilder) {
            deepLinkComposable<TripsRoute.Trips>(TripsRoute.Trips.BASE_PATH) {
                TripsScreen(
                    onBack = onBack,
                    onOpenTravelSenders = { onFinish(TripsRoute.TravelSenders) },
                )
            }
            deepLinkComposable<TripsRoute.TravelSenders>(TripsRoute.TravelSenders.BASE_PATH) {
                TravelSendersScreen(onBack = onBack)
            }
        }
    }
}
