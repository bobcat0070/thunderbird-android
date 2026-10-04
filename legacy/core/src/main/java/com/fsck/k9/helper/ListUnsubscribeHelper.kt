package com.fsck.k9.helper

import android.net.Uri
import com.fsck.k9.mail.Message
import java.util.regex.Pattern

object ListUnsubscribeHelper {
    private const val LIST_UNSUBSCRIBE_HEADER = "List-Unsubscribe"
    private const val ONE_CLICK_POST_VALUE = "List-Unsubscribe=One-Click"
    private val MAILTO_CONTAINER_PATTERN = Pattern.compile("<(mailto:.+?)>")
    private val HTTPS_CONTAINER_PATTERN = Pattern.compile("<(https:.+?)>")

    // As K-9 Mail is an email client, we prefer a mailto: unsubscribe method
    // but if none is found, a https URL is acceptable too
    fun getPreferredListUnsubscribeUri(message: Message): UnsubscribeUri? {
        return getPreferredListUnsubscribeUri(message.getHeader(LIST_UNSUBSCRIBE_HEADER).toList())
    }

    /**
     * The same choice made from the raw header values, for callers that have the stored headers of a message
     * rather than the message itself - triaging from the list, where nothing has been fetched.
     */
    fun getPreferredListUnsubscribeUri(headerValues: List<String>): UnsubscribeUri? {
        if (headerValues.isEmpty()) {
            return null
        }
        val listUnsubscribeUris = mutableListOf<Uri>()
        for (headerValue in headerValues) {
            val uri = extractUri(headerValue) ?: continue

            if (uri.scheme == "mailto") {
                return MailtoUnsubscribeUri(uri)
            }

            // If we got here it must be HTTPS
            listUnsubscribeUris.add(uri)
        }

        if (listUnsubscribeUris.isNotEmpty()) {
            return HttpsUnsubscribeUri(listUnsubscribeUris[0])
        }

        return null
    }

    /**
     * The address to send a one-click unsubscribe to (RFC 8058), or `null` when the message does not offer one.
     *
     * Offered only when the List-Unsubscribe-Post header says exactly that and List-Unsubscribe has an HTTPS address:
     * one-click needs both, and posting to an address that never promised to take a POST could do anything.
     */
    fun getOneClickUnsubscribeUri(listUnsubscribeValues: List<String>, listUnsubscribePostValues: List<String>): Uri? {
        val isOffered = listUnsubscribePostValues.any { it.trim().equals(ONE_CLICK_POST_VALUE, ignoreCase = true) }
        if (!isOffered) return null

        return listUnsubscribeValues.firstNotNullOfOrNull { value ->
            HTTPS_CONTAINER_PATTERN.matcher(value).takeIf { it.find() }?.group(1)?.let(Uri::parse)
        }
    }

    private fun extractUri(headerValue: String?): Uri? {
        if (headerValue == null || headerValue.isEmpty()) {
            return null
        }

        var matcher = MAILTO_CONTAINER_PATTERN.matcher(headerValue)
        if (matcher.find()) {
            return Uri.parse(matcher.group(1))
        }

        matcher = HTTPS_CONTAINER_PATTERN.matcher(headerValue)
        if (matcher.find()) {
            return Uri.parse(matcher.group(1))
        }

        return null
    }
}
