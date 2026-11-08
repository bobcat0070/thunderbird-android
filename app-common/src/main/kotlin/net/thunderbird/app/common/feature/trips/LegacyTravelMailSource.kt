package net.thunderbird.app.common.feature.trips

import android.content.Context
import app.k9mail.legacy.mailstore.MessageStoreManager
import com.fsck.k9.controller.MessagingController
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.internet.MimeUtility
import com.fsck.k9.mailstore.LocalStoreProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.app.common.feature.mailscan.DOWNLOADED_CONDITION
import net.thunderbird.app.common.feature.mailscan.NOT_DOWNLOADED_CONDITION
import net.thunderbird.app.common.feature.mailscan.containsAny
import net.thunderbird.app.common.feature.mailscan.downloadStoredMessage
import net.thunderbird.app.common.feature.mailscan.loadStoredMessage
import net.thunderbird.app.common.feature.mailscan.messageReferenceOf
import net.thunderbird.app.common.feature.mailscan.openStoredMessage
import net.thunderbird.app.common.feature.mailscan.storedMailHeaders
import net.thunderbird.app.common.feature.mailscan.toKey
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.trips.TravelMailContent
import net.thunderbird.feature.trips.TravelMailHeader
import net.thunderbird.feature.trips.TravelMailSource

/**
 * Words in a subject that suggest a booking: confirmations, itineraries, tickets and the kinds of trip.
 */
private val BOOKING_SUBJECT_WORDS = listOf(
    "confirm", "reservation", "reserved", "booking", "booked", "itinerary", "ticket", "your trip", "your stay",
    "flight", "hotel", "check-in", "check in", "boarding", "cruise", "sailing", "rental", "table for",
)

private val BOOKING_CONDITION = containsAny("messages.subject", BOOKING_SUBJECT_WORDS)

/**
 * Words in a subject that say something about a trip, which is enough for mail from a travel sender: its reminders,
 * receipts and welcome mail.
 */
private val TRIP_SUBJECT_WORDS = listOf(
    "order", "receipt", "reminder", "upcoming", "welcome", "trip", "visit", "vacation", "document", "countdown",
    "get ready", "your room", "your cabin", "your car", "your table",
)

private val TRIP_SUBJECT_CONDITION = containsAny("messages.subject", TRIP_SUBJECT_WORDS)

/**
 * A domain as the travel sender list holds it. Checked again here, as domains are written into the query.
 */
private val DOMAIN = Regex("^[a-z0-9-]+(?:\\.[a-z0-9-]+)+$")

/**
 * How many marked mails are looked for, each a query placeholder; SQLite takes 999 by default.
 */
private const val MAX_MARKED_IN_QUERY = 500

/**
 * The largest HTML part read for booking markup; a confirmation is far smaller.
 */
private const val MAX_HTML_SIZE = 512L * 1024

/**
 * Reads booking mail from the stores of every account for the Trips screen.
 */
internal class LegacyTravelMailSource(
    private val context: Context,
    private val accountManager: LegacyAccountManager,
    private val messageStoreManager: MessageStoreManager,
    private val localStoreProvider: LocalStoreProvider,
    private val messagingController: MessagingController,
) : TravelMailSource {

    override suspend fun candidates(
        since: Long,
        senderDomains: Set<String>,
        markedMessageIds: Set<String>,
    ): List<TravelMailHeader> = withContext(Dispatchers.IO) {
        val domains = senderDomains.filter { DOMAIN.matches(it) }
        val marked = markedMessageIds.take(MAX_MARKED_IN_QUERY)

        val senderCondition = domains.takeIf { it.isNotEmpty() }?.let {
            val addressParts = domains.flatMap { domain -> listOf("@$domain", ".$domain") }
            // The few subject words first: SQLite then tests the hundreds of sender patterns only on the mails whose
            // subject says something about a trip, which made a year of mail about three times quicker to search.
            "($TRIP_SUBJECT_CONDITION AND ${containsAny("messages.sender_list", addressParts)})"
        }
        val markedCondition = marked.takeIf { it.isNotEmpty() }?.let {
            "messages.message_id IN (${marked.joinToString(",") { "?" }})"
        }
        val condition = listOfNotNull(BOOKING_CONDITION, senderCondition, markedCondition)
            .joinToString(" OR ", prefix = "(", postfix = ")")
        val conditionArgs = if (markedCondition != null) marked else emptyList()

        val markedKeys = markedCondition?.let {
            headers(since, it, marked).map { header -> header.reference.toKey() }.toSet()
        }.orEmpty()

        listOf(true, false).flatMap { isDownloaded ->
            val downloadCondition = if (isDownloaded) DOWNLOADED_CONDITION else NOT_DOWNLOADED_CONDITION
            headers(since, "$condition AND $downloadCondition", conditionArgs).map { header ->
                val key = header.reference.toKey()
                TravelMailHeader(
                    key = key,
                    date = header.date,
                    senderName = header.senderName,
                    senderAddress = header.senderAddress,
                    subject = header.subject,
                    isDownloaded = isDownloaded,
                    isMarked = key in markedKeys,
                )
            }
        }
    }

    private fun headers(since: Long, condition: String, conditionArgs: List<String>) = storedMailHeaders(
        accountManager = accountManager,
        messageStoreManager = messageStoreManager,
        since = since,
        condition = condition,
        conditionArgs = conditionArgs,
    )

    override suspend fun content(key: String): TravelMailContent? = withContext(Dispatchers.IO) {
        val reference = messageReferenceOf(key) ?: return@withContext null
        val message = loadStoredMessage(localStoreProvider, reference) ?: return@withContext null

        TravelMailContent(
            html = MimeUtility.findFirstPartByMimeType(message, "text/html")
                ?.let { MessageExtractor.getTextFromPart(it, MAX_HTML_SIZE) },
            text = MimeUtility.findFirstPartByMimeType(message, "text/plain")
                ?.let(MessageExtractor::getTextFromPart),
        )
    }

    override suspend fun download(keys: List<String>, onProgress: (done: Int, total: Int) -> Unit): Int {
        var downloaded = 0
        keys.forEachIndexed { index, key ->
            val reference = messageReferenceOf(key)
            if (reference != null && downloadStoredMessage(messagingController, reference)) downloaded++
            onProgress(index + 1, keys.size)
        }
        return downloaded
    }

    override fun openMail(key: String) {
        openStoredMessage(context, key)
    }
}
