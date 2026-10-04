package net.thunderbird.feature.spamdigest

import kotlin.time.Instant

/**
 * An account the digest can cover or be sent from.
 */
public data class SpamDigestAccount(
    val id: String,
    val name: String,
    val email: String,
)

/**
 * Lists the accounts set up in the app.
 */
public fun interface SpamDigestAccounts {
    /**
     * @return every account, in the order the app shows them.
     */
    public fun accounts(): List<SpamDigestAccount>
}

/**
 * A sender authentication mechanism the receiving server reports on.
 */
public enum class SenderCheckMethod {
    SPF,
    DKIM,
    DMARC,
}

/**
 * What the account's own server reported for one mechanism.
 *
 * @param passedAligned whether the mechanism passed and its authenticated domain lined up with the From domain.
 *   `false` covers a failure, a pass for some unrelated domain, and a mechanism the server did not check.
 */
public data class SenderCheck(
    val method: SenderCheckMethod,
    val passedAligned: Boolean,
)

/**
 * One message found in a spam folder.
 *
 * Everything here was written by whoever sent the message and must be treated as hostile text.
 *
 * @param senderChecks one entry per [SenderCheckMethod], or empty when the account's own server reported nothing
 *   that can be trusted.
 */
public data class SpamMessage(
    val senderName: String?,
    val senderAddress: String?,
    val subject: String?,
    val receivedAt: Instant,
    val senderChecks: List<SenderCheck>,
)

/**
 * What a spam folder held over a period.
 *
 * @param isRefreshed whether the folder was brought up to date with the server first. When it was not, the
 *   list holds only what had already been synced.
 */
public data class SpamFolderContents(
    val messages: List<SpamMessage>,
    val isRefreshed: Boolean,
)

/**
 * Reads what arrived in an account's spam folder.
 */
public interface SpamFolderReader {
    /**
     * Refreshes the account's spam folder from the server, then lists what arrived in it from [from] (inclusive)
     * until [until] (exclusive), oldest first.
     *
     * @return the folder's contents, or `null` when the account has no spam folder.
     */
    public suspend fun read(accountId: String, from: Instant, until: Instant): SpamFolderContents?
}

/**
 * Sends the digest.
 */
public interface SpamDigestMailer {
    /**
     * Sends a plain-text message from [accountId] to that account's own address.
     *
     * Returns once the message is queued for sending; delivery itself goes through the account's outbox like
     * any other message, with its retries.
     */
    public suspend fun sendToSelf(accountId: String, subject: String, body: String)
}

/**
 * Keeps the next digest scheduled.
 */
public interface SpamDigestScheduler {
    /**
     * Schedules the next digest from the current settings, replacing any already scheduled, or cancels it when
     * the digest is off.
     */
    public fun reschedule()

    /**
     * Schedules the next digest unless one is already scheduled. Safe to call on every app start.
     */
    public fun ensureScheduled()
}
