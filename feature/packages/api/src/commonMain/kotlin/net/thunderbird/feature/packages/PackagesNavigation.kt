package net.thunderbird.feature.packages

import kotlinx.serialization.Serializable
import net.thunderbird.core.ui.navigation.Navigation
import net.thunderbird.core.ui.navigation.Route

const val PACKAGES_BASE_DEEP_LINK = "app://feature/packages"

sealed interface PackagesRoute : Route {
    /**
     * The parcels on their way to the reader, gathered from shipping mail, and those delivered lately.
     */
    @Serializable
    data object Packages : PackagesRoute {
        override val basePath: String = BASE_PATH

        override fun route(): String = basePath

        const val BASE_PATH = "$PACKAGES_BASE_DEEP_LINK/list"
    }

    /**
     * The reader's own UPS, FedEx and USPS developer credentials, for live tracking status.
     */
    @Serializable
    data object CarrierAccounts : PackagesRoute {
        override val basePath: String = BASE_PATH

        override fun route(): String = basePath

        const val BASE_PATH = "$PACKAGES_BASE_DEEP_LINK/carrier_accounts"
    }
}

interface PackagesNavigation : Navigation<PackagesRoute>
