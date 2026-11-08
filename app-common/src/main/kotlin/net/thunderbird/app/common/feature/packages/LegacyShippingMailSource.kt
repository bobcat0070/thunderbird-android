package net.thunderbird.app.common.feature.packages

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.k9mail.legacy.mailstore.MessageStoreManager
import com.fsck.k9.activity.MessageSearchActivity
import com.fsck.k9.controller.MessagingController
import com.fsck.k9.mailstore.LocalStoreProvider
import com.fsck.k9.message.extractors.Carrier
import com.fsck.k9.message.extractors.findTrackingNumbers
import com.fsck.k9.message.extractors.messagePlainText
import com.fsck.k9.view.LinkConfirmation
import com.fsck.k9.view.LinkConfirmationActivity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.thunderbird.app.common.feature.mailscan.DOWNLOADED_CONDITION
import net.thunderbird.app.common.feature.mailscan.NOT_DOWNLOADED_CONDITION
import net.thunderbird.app.common.feature.mailscan.StoredMailHeader
import net.thunderbird.app.common.feature.mailscan.containsAny
import net.thunderbird.app.common.feature.mailscan.downloadStoredMessage
import net.thunderbird.app.common.feature.mailscan.loadStoredMessage
import net.thunderbird.app.common.feature.mailscan.openStoredMessage
import net.thunderbird.app.common.feature.mailscan.storedMailHeaders
import net.thunderbird.app.common.feature.mailscan.toKey
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.packages.ParcelCarrier
import net.thunderbird.feature.packages.ParcelNumber
import net.thunderbird.feature.packages.ShippingMail
import net.thunderbird.feature.packages.ShippingMailSource

/**
 * Words in a subject that make a mail worth reading for tracking numbers.
 */
private val SHIPPING_SUBJECT_WORDS = listOf(
    "ship", "track", "deliver", "package", "parcel", "order", "dispatch", "on its way", "on the way", "in transit",
    "arriving", "usps", "fedex", "dhl", "ups ",
)

/**
 * Words in the stored preview that do the same, for shops whose subjects say nothing.
 */
private val SHIPPING_PREVIEW_WORDS = listOf("tracking", "1z")

private val SHIPPING_CONDITION =
    "(${containsAny("messages.subject", SHIPPING_SUBJECT_WORDS)} OR " +
        "${containsAny("messages.preview", SHIPPING_PREVIEW_WORDS)})"

/**
 * How much of a mail's text is kept for reading its status: an update says where the parcel is near the top.
 */
private const val MAX_TEXT_LENGTH = 4000

/**
 * Reads shipping mail from the stores of every account for the Packages screen.
 *
 * What each mail holds is kept for as long as the app runs, so opening Packages again reads only new mail.
 */
@Suppress("LongParameterList")
internal class LegacyShippingMailSource(
    private val context: Context,
    private val accountManager: LegacyAccountManager,
    private val messageStoreManager: MessageStoreManager,
    private val localStoreProvider: LocalStoreProvider,
    private val messagingController: MessagingController,
    private val linkConfirmation: LinkConfirmation,
) : ShippingMailSource {
    /**
     * Each mail read so far, or `null` for one that turned out to name no tracking number.
     */
    private val readMails = ConcurrentHashMap<String, CachedMail>()

    private class CachedMail(val mail: ShippingMail?)

    override suspend fun shippingMails(since: Long): List<ShippingMail> = withContext(Dispatchers.IO) {
        headers(since, DOWNLOADED_CONDITION).mapNotNull { header ->
            val key = header.reference.toKey()
            readMails.getOrPut(key) { CachedMail(read(header, key)) }.mail
        }
    }

    @Suppress("ReturnCount")
    private fun read(header: StoredMailHeader, key: String): ShippingMail? {
        val message = loadStoredMessage(localStoreProvider, header.reference) ?: return null
        val parcels = findTrackingNumbers(message, header.subject).map { trackingNumber ->
            ParcelNumber(
                carrier = trackingNumber.carrier.toParcelCarrier(),
                number = trackingNumber.number,
                trackingUrl = trackingNumber.url,
            )
        }
        if (parcels.isEmpty()) return null

        val text = listOfNotNull(header.subject, messagePlainText(message)).joinToString("\n").take(MAX_TEXT_LENGTH)
        return ShippingMail(
            key = key,
            date = header.date,
            senderName = header.senderName,
            subject = header.subject,
            parcels = parcels,
            text = text,
        )
    }

    override suspend fun notDownloadedCount(since: Long): Int = withContext(Dispatchers.IO) {
        headers(since, NOT_DOWNLOADED_CONDITION).size
    }

    override suspend fun downloadMails(
        since: Long,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Int {
        val headers = withContext(Dispatchers.IO) { headers(since, NOT_DOWNLOADED_CONDITION) }
        var downloaded = 0
        headers.forEachIndexed { index, header ->
            if (downloadStoredMessage(messagingController, header.reference)) downloaded++
            onProgress(index + 1, headers.size)
        }
        return downloaded
    }

    override fun openMail(key: String) {
        openStoredMessage(context, key)
    }

    override fun searchAllMail(query: String) {
        val intent = Intent(context, MessageSearchActivity::class.java)
            .setAction(Intent.ACTION_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Opens straight away a carrier's page built from a tracking number; anything else goes through the link check.
     */
    override fun openTrackingPage(url: String) {
        if (!linkConfirmation.openTrackingPage(context, url)) {
            LinkConfirmationActivity.start(context, Uri.parse(url))
        }
    }

    private fun headers(since: Long, downloadCondition: String): List<StoredMailHeader> = storedMailHeaders(
        accountManager = accountManager,
        messageStoreManager = messageStoreManager,
        since = since,
        condition = "$SHIPPING_CONDITION AND $downloadCondition",
    )
}

private fun Carrier.toParcelCarrier(): ParcelCarrier = when (this) {
    Carrier.UPS -> ParcelCarrier.UPS
    Carrier.USPS -> ParcelCarrier.USPS
    Carrier.FEDEX -> ParcelCarrier.FEDEX
    Carrier.DHL -> ParcelCarrier.DHL
}
