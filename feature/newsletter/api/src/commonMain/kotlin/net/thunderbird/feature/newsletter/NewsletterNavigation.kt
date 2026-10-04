package net.thunderbird.feature.newsletter

import kotlinx.serialization.Serializable
import net.thunderbird.core.ui.navigation.Navigation
import net.thunderbird.core.ui.navigation.Route

const val NEWSLETTER_BASE_DEEP_LINK = "app://feature/newsletter"

sealed interface NewsletterRoute : Route {
    /**
     * Everyone who sends the reader newsletters, most prolific first.
     */
    @Serializable
    data object Senders : NewsletterRoute {
        override val basePath: String = BASE_PATH

        override fun route(): String = basePath

        const val BASE_PATH = "$NEWSLETTER_BASE_DEEP_LINK/senders"
    }
}

interface NewsletterNavigation : Navigation<NewsletterRoute>
