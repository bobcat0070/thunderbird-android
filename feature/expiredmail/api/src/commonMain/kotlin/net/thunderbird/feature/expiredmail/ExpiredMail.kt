package net.thunderbird.feature.expiredmail

import kotlinx.serialization.Serializable
import net.thunderbird.core.ui.navigation.Navigation
import net.thunderbird.core.ui.navigation.Route

const val EXPIRED_MAIL_BASE_DEEP_LINK = "app://feature/expiredmail"

sealed interface ExpiredMailRoute : Route {
    /**
     * Mail whose use is over, grouped by why.
     */
    @Serializable
    data object Overview : ExpiredMailRoute {
        override val basePath: String = BASE_PATH

        override fun route(): String = basePath

        const val BASE_PATH = "$EXPIRED_MAIL_BASE_DEEP_LINK/list"
    }
}

interface ExpiredMailNavigation : Navigation<ExpiredMailRoute>

/**
 * Why a mail is no longer of use.
 */
enum class ExpiredKind {
    /** A sign-in or verification mail, more than a day old: its code or link has run out. */
    SIGN_IN,

    /** Shipping mail for a parcel delivered more than two weeks ago. */
    DELIVERED_PARCEL,
}

/**
 * A mail still in the reader's folders whose use is over.
 *
 * @param key what the app needs to find the mail again; opaque to the screen.
 * @param date when the mail was sent, in milliseconds since the epoch.
 */
data class ExpiredMail(
    val key: String,
    val kind: ExpiredKind,
    val date: Long,
    val senderName: String?,
    val subject: String?,
)

/**
 * Finds expired mail in every account and acts on it. Mail already archived, in the trash, in spam, or the reader's
 * own is never listed.
 */
interface ExpiredMailSource {
    /**
     * @return the expired mail, newest first.
     */
    suspend fun expiredMail(): List<ExpiredMail>

    /**
     * @return how many of the mails were archived; mail in an account without an archive folder is left where it is.
     */
    suspend fun archive(keys: List<String>): Int

    /**
     * Moves the mails to the trash.
     *
     * @return how many were moved.
     */
    suspend fun delete(keys: List<String>): Int

    fun openMail(key: String)
}
