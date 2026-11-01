package net.thunderbird.feature.packages.internal

/**
 * Where a parcel is, as far as the reader can be told.
 */
enum class ParcelStatus {
    ON_ITS_WAY,
    OUT_FOR_DELIVERY,
    DELIVERED,

    /** A delay, a missed delivery, a parcel held or returned: something the reader may have to act on. */
    PROBLEM,
}

private val OUT_FOR_DELIVERY = Regex("""(?i)\bout for delivery\b""")

/**
 * "Delivered", but not "will be delivered" or "to be delivered", which are promises.
 */
private val DELIVERED_SUBJECT = Regex("""(?i)(?<!\bbe )(?<!\bbeing )\bdelivered\b""")

/**
 * In a mail's text "delivered" turns up in every footer ("Delivered by", "Deliver to"), so only phrases that say
 * the parcel has arrived count there.
 */
private val DELIVERED_TEXT = Regex(
    """(?i)\b(?:has been delivered|was delivered|is delivered|package delivered|parcel delivered|""" +
        """delivered (?:on|at|to your)\b|left at (?:the |your )?(?:front door|door|porch|mailbox|mailroom|""" +
        """parcel locker))""",
)

private val PROBLEM_SUBJECT = Regex(
    """(?i)\b(?:delivery exception|exception|attempted delivery|delivery attempt|unable to deliver|""" +
        """could not be delivered|couldn't deliver|delayed|delay|held at|returned to sender|""" +
        """return to sender|action required|damaged)\b""",
)

/**
 * Text speaks of what may go wrong ("shipping delays may occur", "if delivery is attempted") in every update, so
 * only phrases that say it did go wrong count there.
 */
private val PROBLEM_TEXT = Regex(
    """(?i)\b(?:delivery exception|we attempted (?:a )?delivery|delivery was attempted|unable to deliver|""" +
        """could not be delivered|is delayed|has been delayed|returned to (?:the )?sender)\b""",
)

/**
 * "Out for delivery" in text is mostly a promise ("we'll tell you when it's out for delivery").
 */
private val OUT_FOR_DELIVERY_TEXT = Regex("""(?i)\b(?:is|are) (?:now )?out for delivery\b""")

/**
 * Reads where a parcel is from a shipping update: its subject first, since that is where carriers and shops put it,
 * then its text, for phrases that leave no doubt.
 *
 * @return the status the mail reports, or `null` when it says nothing about one - an order confirmation, say.
 */
fun mailStatus(subject: String?, text: String): ParcelStatus? {
    subject?.let(::statusInSubject)?.let { return it }
    return statusInText(text)
}

private fun statusInSubject(subject: String): ParcelStatus? = when {
    OUT_FOR_DELIVERY.containsMatchIn(subject) -> ParcelStatus.OUT_FOR_DELIVERY
    DELIVERED_SUBJECT.containsMatchIn(subject) -> ParcelStatus.DELIVERED
    PROBLEM_SUBJECT.containsMatchIn(subject) -> ParcelStatus.PROBLEM
    else -> null
}

private fun statusInText(text: String): ParcelStatus? = when {
    DELIVERED_TEXT.containsMatchIn(text) -> ParcelStatus.DELIVERED
    OUT_FOR_DELIVERY_TEXT.containsMatchIn(text) -> ParcelStatus.OUT_FOR_DELIVERY
    PROBLEM_TEXT.containsMatchIn(text) -> ParcelStatus.PROBLEM
    else -> null
}
