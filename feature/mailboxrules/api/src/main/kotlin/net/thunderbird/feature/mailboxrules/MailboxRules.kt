package net.thunderbird.feature.mailboxrules

import android.content.Intent
import kotlinx.serialization.Serializable
import net.thunderbird.core.ui.navigation.Navigation
import net.thunderbird.core.ui.navigation.Route

const val MAILBOX_RULES_BASE_DEEP_LINK = "app://feature/mailboxrules"

sealed interface MailboxRulesRoute : Route {
    /**
     * The inbox rules of the reader's Microsoft 365 mailboxes, those that send mail away or make it disappear first.
     */
    @Serializable
    data object Rules : MailboxRulesRoute {
        override val basePath: String = BASE_PATH

        override fun route(): String = basePath

        const val BASE_PATH = "$MAILBOX_RULES_BASE_DEEP_LINK/rules"
    }
}

interface MailboxRulesNavigation : Navigation<MailboxRulesRoute>

/**
 * A Microsoft 365 mailbox whose rules can be read.
 */
data class RulesAccount(val id: String, val email: String)

/**
 * An inbox rule, as much of it as says whether it sends mail away or hides it.
 *
 * @param forwardsTo every address the rule forwards or redirects mail to.
 * @param deletes whether the rule deletes mail, to the deleted items or for good.
 * @param movesAndMarksRead whether the rule moves mail elsewhere and marks it read, so it never shows as new.
 */
data class MailboxRule(
    val name: String,
    val isEnabled: Boolean,
    val forwardsTo: List<String>,
    val deletes: Boolean,
    val movesAndMarksRead: Boolean,
)

/**
 * What reading a mailbox's rules came to.
 */
sealed interface RulesResult {
    data class Success(val rules: List<MailboxRule>) : RulesResult

    /** The reader closed the sign-in. */
    data object Canceled : RulesResult

    /** The sign-in or Microsoft refused the permission, as an organisation's administrator can require. */
    data class Refused(val reason: String?) : RulesResult

    /** The rules could not be read, for want of a connection or otherwise. */
    data class Failed(val reason: String?) : RulesResult
}

/**
 * Reads the inbox rules of Microsoft 365 mailboxes.
 *
 * Reading them takes a permission the mail sign-in does not ask for, "read your mailbox settings", so each reading
 * starts with a sign-in of its own that asks for it alone. What that sign-in grants is used for the one reading and
 * kept nowhere; the account's own sign-in is not touched.
 */
interface MailboxRulesSource {
    fun accounts(): List<RulesAccount>

    /**
     * @return the intent that starts the sign-in for [accountId], or `null` when it cannot be started.
     */
    fun signInIntent(accountId: String): Intent?

    /**
     * Finishes the sign-in with the intent it returned and reads the account's rules.
     *
     * @param signInResult what the sign-in returned; `null` when it was closed.
     */
    suspend fun readRules(accountId: String, signInResult: Intent?): RulesResult

    /**
     * Frees what the sign-in holds, once the screen is gone.
     */
    fun close()
}
