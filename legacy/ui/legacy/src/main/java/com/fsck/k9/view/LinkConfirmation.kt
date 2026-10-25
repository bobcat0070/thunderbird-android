package com.fsck.k9.view

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.provider.Browser
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.text.inSpans
import com.fsck.k9.message.html.StrippedLink
import com.fsck.k9.message.html.clickTrackerHost
import com.fsck.k9.message.html.hostOf
import com.fsck.k9.message.html.senderWebsite
import com.fsck.k9.message.html.shortenForDisplay
import com.fsck.k9.message.html.stripTracking
import com.fsck.k9.ui.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.thunderbird.core.preference.GeneralSettingsManager
import net.thunderbird.feature.blocklist.BlocklistKind
import net.thunderbird.feature.blocklist.BlocklistMatch
import net.thunderbird.feature.blocklist.DomainBlocklist

/**
 * Shows a web link from a message before opening it, with the choice of not going, going as it is, or going without
 * what tracks the click - for every link the sender chose: the ones in the message, and the unsubscribe page a
 * newsletter names.
 *
 * Other links, such as `mailto:` and `tel:`, open straight away: they have nothing to strip and do not leave the
 * device. So does every web link when the user has turned the link check off - except a deceptive one and one on the
 * threat blocklist, which are warned about either way. Being on a blocklist is only ever said, never a reason to
 * refuse the link.
 */
class LinkConfirmation(
    private val generalSettingsManager: GeneralSettingsManager,
    private val domainBlocklist: DomainBlocklist,
) {
    /**
     * @param shownSite the site the link's text named, when that is a different site from the one it opens - which
     *   is what nearly every phishing link looks like - or `null`.
     * @param textAddress the web address the link's text is, when its text is one, e.g. `https://costco.com/orders`
     *   written over a click tracker.
     * @param senderAddress the address of whoever sent the message the link is in.
     * @param onClosed called once the link has been opened or the dialog closed, whichever way.
     */
    @Suppress("LongParameterList")
    fun confirmAndOpen(
        context: Context,
        uri: Uri,
        shownSite: String? = null,
        textAddress: String? = null,
        senderAddress: String? = null,
        onClosed: () -> Unit = {},
    ) {
        val url = uri.toString()
        val host = hostOf(url)
        if (host == null) {
            openUrl(context, uri)
            onClosed()
            return
        }

        val stripped = stripTracking(url)
        val listed = listing(host, stripped)
        val mustWarn = shownSite != null || listed?.match?.kind == BlocklistKind.THREAT
        if (!mustWarn && !generalSettingsManager.getConfig().interaction.isLinkCheckEnabled) {
            openUrl(context, uri)
            onClosed()
            return
        }

        val alternative = alternative(host, url, textAddress, senderAddress)
        val alternativeView = alternative?.let {
            LayoutInflater.from(context).inflate(R.layout.dialog_link_alternative, null)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (shownSite != null) R.string.deceptive_link_title else R.string.link_dialog_title)
            .setMessage(dialogMessage(context, uri, shownSite, listed, stripped))
            .apply { alternativeView?.let(::setView) }
            .setNegativeButton(R.string.link_dialog_dont_open, null)
            .setNeutralButton(R.string.link_dialog_open_as_is) { _, _ -> openUrl(context, uri) }
            .setPositiveButton(R.string.link_dialog_strip) { _, _ ->
                stripped?.let { openUrl(context, Uri.parse(it.url)) }
            }
            .setOnDismissListener { onClosed() }
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = stripped != null
        if (alternative != null && alternativeView != null) {
            alternativeView.findViewById<Button>(R.id.link_alternative).apply {
                text = context.getString(R.string.link_dialog_go_to_instead, alternative.label)
                setOnClickListener {
                    dialog.dismiss()
                    openUrl(context, Uri.parse(alternative.url))
                }
            }
        }
    }

    /**
     * Somewhere to go instead of the link, reached without any of its tracking: the address the link's text names,
     * when it names one, otherwise the sender's own website. Not offered when it is where the link goes anyway, or
     * when it is on the threat list.
     */
    private fun alternative(host: String, url: String, textAddress: String?, senderAddress: String?): Alternative? {
        val candidates = listOfNotNull(
            textAddress?.takeIf { it != url },
            senderWebsite(senderAddress),
        )

        return candidates.firstNotNullOfOrNull { candidate ->
            val candidateHost = hostOf(candidate) ?: return@firstNotNullOfOrNull null
            val isThreat = domainBlocklist.match(candidateHost)?.kind == BlocklistKind.THREAT
            val isSameSite = candidate == senderWebsite(senderAddress) && candidateHost == host
            Alternative(candidate, candidateHost).takeIf { !isThreat && !isSameSite }
        }
    }

    /**
     * The link's own site is looked up first. When it is not listed and the link goes through a redirect - Safe
     * Links wraps every link in Microsoft 365 mail - the site the redirect leads to is looked up instead, since that
     * is where the reader actually ends up.
     */
    private fun listing(host: String, stripped: StrippedLink?): Listing? {
        domainBlocklist.match(host)?.let { return Listing(it, isDestination = false) }

        val destinationHost = stripped?.takeIf { it.skippedRedirects.isNotEmpty() }?.url?.let(::hostOf)
        return destinationHost?.let(domainBlocklist::match)?.let { Listing(it, isDestination = true) }
    }

    private fun dialogMessage(
        context: Context,
        uri: Uri,
        shownSite: String?,
        listed: Listing?,
        stripped: StrippedLink?,
    ): CharSequence {
        return SpannableStringBuilder().apply {
            if (shownSite != null) {
                append(context.getString(R.string.deceptive_link_message, shownSite, uri.host.orEmpty()))
                append("\n\n")
            }

            inSpans(StyleSpan(Typeface.BOLD)) { append(uri.host.orEmpty()) }
            append("\n")
            append(shortenForDisplay(uri.toString()))
            append("\n\n")

            listed?.let { listing ->
                append(
                    if (listing.isDestination) {
                        destinationBlocklistNote(context, listing.match)
                    } else {
                        blocklistNote(context, listing.match)
                    },
                )
                append("\n\n")
            }

            if (stripped == null) {
                val tracker = clickTrackerHost(uri.toString())
                append(
                    if (tracker != null) {
                        context.getString(R.string.link_dialog_click_tracker, tracker)
                    } else {
                        context.getString(R.string.link_dialog_nothing_to_strip)
                    },
                )
                return@apply
            }

            inSpans(StyleSpan(Typeface.BOLD)) { append(context.getString(R.string.link_dialog_stripped_heading)) }
            append("\n")
            append(shortenForDisplay(stripped.url))
            stripped.skippedRedirects.forEach { host ->
                append("\n")
                append(context.getString(R.string.link_dialog_skips_redirect, host))
            }
            if (stripped.removedParameters.isNotEmpty()) {
                append("\n")
                append(context.getString(R.string.link_dialog_removes, stripped.removedParameters.joinToString(", ")))
            }
        }
    }

    private fun openUrl(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            putExtra(Browser.EXTRA_APPLICATION_ID, context.packageName)
            putExtra(Browser.EXTRA_CREATE_NEW_TAB, true)

            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }

        try {
            context.startActivity(intent)
        } catch (@Suppress("SwallowedException") e: ActivityNotFoundException) {
            // Not logged: the link is the sender's and may carry a token identifying the reader.
            Toast.makeText(context, R.string.error_activity_not_found, Toast.LENGTH_LONG).show()
        }
    }

    private data class Listing(val match: BlocklistMatch, val isDestination: Boolean)

    private data class Alternative(val url: String, val label: String)
}
