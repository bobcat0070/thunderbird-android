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

    override suspend fun candidates(since: Long): List<TravelMailHeader> = withContext(Dispatchers.IO) {
        headers(since, DOWNLOADED_CONDITION, isDownloaded = true) +
            headers(since, NOT_DOWNLOADED_CONDITION, isDownloaded = false)
    }

    private fun headers(since: Long, downloadCondition: String, isDownloaded: Boolean) = storedMailHeaders(
        accountManager = accountManager,
        messageStoreManager = messageStoreManager,
        since = since,
        condition = "$BOOKING_CONDITION AND $downloadCondition",
    ).map { header ->
        TravelMailHeader(
            key = header.reference.toKey(),
            date = header.date,
            senderName = header.senderName,
            subject = header.subject,
            isDownloaded = isDownloaded,
        )
    }

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
