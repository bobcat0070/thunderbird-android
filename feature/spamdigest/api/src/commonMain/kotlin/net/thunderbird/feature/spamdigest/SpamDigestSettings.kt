package net.thunderbird.feature.spamdigest

/**
 * The time of day the digest is sent, in the device's time zone.
 */
public data class SpamDigestTime(
    val hour: Int,
    val minute: Int,
) {
    init {
        require(hour in 0..MAX_HOUR) { "hour out of range: $hour" }
        require(minute in 0..MAX_MINUTE) { "minute out of range: $minute" }
    }

    public companion object {
        private const val MAX_HOUR = 23
        private const val MAX_MINUTE = 59
        private const val DEFAULT_HOUR = 7

        public val DEFAULT: SpamDigestTime = SpamDigestTime(hour = DEFAULT_HOUR, minute = 0)
    }
}

/**
 * How the daily spam digest is set up.
 *
 * One digest covers every included account and is sent once a day, from one account to that account's own
 * address. Accounts are included unless switched off, so an account added later is covered without the user
 * having to remember to opt it in.
 *
 * @param senderAccountId the account the digest is sent from and to, or `null` when the digest is off.
 * @param excludedAccountIds accounts whose spam folder is left out of the digest.
 * @param sendTime when the digest is sent.
 */
public data class SpamDigestSettings(
    val senderAccountId: String?,
    val excludedAccountIds: Set<String>,
    val sendTime: SpamDigestTime,
) {
    val isEnabled: Boolean
        get() = senderAccountId != null

    public fun isAccountIncluded(accountId: String): Boolean = accountId !in excludedAccountIds
}

/**
 * Reads and changes [SpamDigestSettings]. Every change takes effect on the schedule straight away.
 */
public interface SpamDigestSettingsRepository {
    public fun getSettings(): SpamDigestSettings

    public fun setAccountIncluded(accountId: String, included: Boolean)

    /**
     * Makes [accountId] the account the digest is sent from, or turns the digest off when `null`. There is only
     * ever one, so choosing an account replaces whichever was chosen before.
     */
    public fun setSenderAccount(accountId: String?)

    public fun setSendTime(time: SpamDigestTime)
}
