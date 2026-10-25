package com.fsck.k9.view

import com.fsck.k9.message.html.StrippedLink
import com.fsck.k9.message.html.clickTrackerHost
import com.fsck.k9.message.html.findDeceptiveLinks
import com.fsck.k9.message.html.hostOf
import com.fsck.k9.message.html.shortenForDisplay
import com.fsck.k9.message.html.stripTracking
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.provider.Browser
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import androidx.appcompat.app.AlertDialog
import androidx.core.text.inSpans
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.fsck.k9.helper.ClipboardManager
import com.fsck.k9.mailstore.AttachmentResolver
import com.fsck.k9.ui.R
import com.fsck.k9.view.MessageWebView.OnPageFinishedListener
import net.thunderbird.core.preference.GeneralSettingsManager
import net.thunderbird.feature.blocklist.BlocklistKind
import net.thunderbird.feature.blocklist.BlocklistMatch
import net.thunderbird.feature.blocklist.DomainBlocklist
import net.thunderbird.legacy.logging.Log

private const val TAG = "K9WebViewClient"

/**
 * [WebViewClient] that intercepts requests for `cid:` URIs to load the respective body part.
 */
@Suppress("TooManyFunctions")
class K9WebViewClient(
    private val clipboardManager: ClipboardManager,
    private val generalSettingsManager: GeneralSettingsManager,
    private val domainBlocklist: DomainBlocklist,
    private val attachmentResolver: AttachmentResolver?,
    private val onPageFinishedListener: OnPageFinishedListener?,
    private val displayedHtml: String? = null,
) : WebViewClient() {

    /**
     * The links in the message that name one site while going to another, worked out the first time a link is
     * opened rather than on every message shown.
     */
    private val deceptiveLinks: Map<String, String> by lazy {
        displayedHtml?.let(::findDeceptiveLinks).orEmpty()
    }

    @Deprecated("Deprecated in parent class")
    override fun shouldOverrideUrlLoading(webView: WebView, url: String): Boolean {
        return shouldOverrideUrlLoading(webView, Uri.parse(url))
    }

    override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean {
        return shouldOverrideUrlLoading(webView, request.url)
    }

    private fun shouldOverrideUrlLoading(webView: WebView, uri: Uri): Boolean {
        return when (uri.scheme) {
            CID_SCHEME -> {
                false
            }
            FILE_SCHEME -> {
                copyUrlToClipboard(webView.context, uri)
                true
            }
            else -> {
                confirmAndOpen(webView.context, uri)
                true
            }
        }
    }

    private fun copyUrlToClipboard(context: Context, uri: Uri) {
        val label = context.getString(R.string.webview_contextmenu_link_clipboard_label)
        clipboardManager.setText(label, uri.toString())
    }

    /**
     * Shows a web link before opening it, with the choice of not going, going as it is, or going without what
     * tracks the click. When the link's text named a different site from the one it goes to - which is what nearly
     * every phishing link looks like - the dialog says so first.
     *
     * Other links, such as `mailto:` and `tel:`, open straight away: they have nothing to strip and do not leave
     * the device. So does every web link when the user has turned the link check off - except a deceptive one,
     * which is warned about either way, as it was before the check existed, and one on the threat blocklist.
     * Being on a blocklist is only ever said, never a reason to refuse the link.
     */
    fun confirmAndOpen(context: Context, uri: Uri) {
        val url = uri.toString()
        val host = hostOf(url)
        if (host == null) {
            openUrl(context, uri)
            return
        }

        val shownSite = deceptiveLinks[host]
        val listed = domainBlocklist.match(host)
        val mustWarn = shownSite != null || listed?.kind == BlocklistKind.THREAT
        if (!mustWarn && !generalSettingsManager.getConfig().interaction.isLinkCheckEnabled) {
            openUrl(context, uri)
            return
        }

        val stripped = stripTracking(url)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (shownSite != null) R.string.deceptive_link_title else R.string.link_dialog_title)
            .setMessage(linkDialogMessage(context, uri, shownSite, listed, stripped))
            .setNegativeButton(R.string.link_dialog_dont_open, null)
            .setNeutralButton(R.string.link_dialog_open_as_is) { _, _ -> openUrl(context, uri) }
            .setPositiveButton(R.string.link_dialog_strip) { _, _ ->
                stripped?.let { openUrl(context, Uri.parse(it.url)) }
            }
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = stripped != null
    }

    private fun linkDialogMessage(
        context: Context,
        uri: Uri,
        shownSite: String?,
        listed: BlocklistMatch?,
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

            listed?.let { match ->
                append(blocklistNote(context, match))
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
        } catch (e: ActivityNotFoundException) {
            Log.d(TAG, e, "Couldn't open URL: %s", uri)
            Toast.makeText(context, R.string.error_activity_not_found, Toast.LENGTH_LONG).show()
        }
    }

    override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url

        return if (uri.scheme == CID_SCHEME) {
            handleCidUri(uri, webView)
        } else {
            RESULT_DO_NOT_INTERCEPT
        }
    }

    private fun handleCidUri(uri: Uri, webView: WebView): WebResourceResponse {
        val attachmentUri = getAttachmentUriFromCidUri(uri) ?: return RESULT_DUMMY_RESPONSE

        val context = webView.context
        val contentResolver = context.contentResolver

        @Suppress("TooGenericExceptionCaught")
        return try {
            val mimeType = contentResolver.getType(attachmentUri)
            val inputStream = contentResolver.openInputStream(attachmentUri)

            WebResourceResponse(mimeType, null, inputStream).apply {
                addCacheControlHeader()
            }
        } catch (e: Exception) {
            Log.e(TAG, e, "Error while intercepting URI: %s", uri)
            RESULT_DUMMY_RESPONSE
        }
    }

    private fun getAttachmentUriFromCidUri(uri: Uri): Uri? {
        return uri.schemeSpecificPart
            ?.let { cid -> attachmentResolver?.getAttachmentUriForContentId(cid) }
    }

    private fun WebResourceResponse.addCacheControlHeader() {
        responseHeaders = mapOf("Cache-Control" to "no-store")
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)

        onPageFinishedListener?.onPageFinished()
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        super.onReceivedError(view, request, error)
        if (request.isForMainFrame) {
            Log.d(TAG, "Message WebView load error: %d %s for %s", error.errorCode, error.description, request.url)
        }
    }

    companion object {
        private const val CID_SCHEME = "cid"
        private const val FILE_SCHEME = "file"

        private val RESULT_DO_NOT_INTERCEPT: WebResourceResponse? = null
        private val RESULT_DUMMY_RESPONSE = WebResourceResponse(null, null, null)
    }
}
