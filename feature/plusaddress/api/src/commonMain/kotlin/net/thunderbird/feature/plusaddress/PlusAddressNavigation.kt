package net.thunderbird.feature.plusaddress

import kotlinx.serialization.Serializable
import net.thunderbird.core.ui.navigation.Navigation
import net.thunderbird.core.ui.navigation.Route

const val PLUS_ADDRESS_BASE_DEEP_LINK = "app://feature/plusaddress"

sealed interface PlusAddressRoute : Route {
    /**
     * The reader's plus addresses, the site each was given to, and who else has used it.
     */
    @Serializable
    data object PlusAddresses : PlusAddressRoute {
        override val basePath: String = BASE_PATH

        override fun route(): String = basePath

        const val BASE_PATH = "$PLUS_ADDRESS_BASE_DEEP_LINK/list"
    }
}

interface PlusAddressNavigation : Navigation<PlusAddressRoute>
