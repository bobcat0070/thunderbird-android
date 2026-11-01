package net.thunderbird.feature.trips

import kotlinx.serialization.Serializable
import net.thunderbird.core.ui.navigation.Navigation
import net.thunderbird.core.ui.navigation.Route

const val TRIPS_BASE_DEEP_LINK = "app://feature/trips"

sealed interface TripsRoute : Route {
    /**
     * The reader's upcoming trips, gathered from booking confirmations in their mail.
     */
    @Serializable
    data object Trips : TripsRoute {
        override val basePath: String = BASE_PATH

        override fun route(): String = basePath

        const val BASE_PATH = "$TRIPS_BASE_DEEP_LINK/list"
    }
}

interface TripsNavigation : Navigation<TripsRoute>
