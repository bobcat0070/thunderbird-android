package com.fsck.k9.view

import com.fsck.k9.message.html.findAddressTexts
import com.fsck.k9.message.html.findDeceptiveLinks
import com.fsck.k9.message.html.hostOf
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fsck.k9.helper.ClipboardManager
import com.fsck.k9.mailstore.AttachmentResolver
import com.fsck.k9.ui.R
import com.fsck.k9.view.MessageWebView.OnPageFinishedListener
import net.thunderbird.legacy.logging.Log

private const val TAG = "K9WebViewClient"

/**
 * [WebViewClient] that intercepts requests for `cid:` URIs to load the respective body part.
 */
@Suppress("TooManyFunctions")
class K9WebViewClient(
    private val clipboardManager: ClipboardManager,
    private val linkConfirmation: LinkConfirmation,
    private val attachmentResolver: AttachmentResolver?,
    private val onPageFinishedListener: OnPageFinishedListener?,
    private val displayedHtml: String? = null,
    private val senderAddress: String? = null,
) : WebViewClient() {

    /**
     * The links in the message that name one site while going to another, worked out the first time a link is
     * opened rather than on every message shown.
     */
    private val deceptiveLinks: Map<String, String> by lazy {
        displayedHtml?.let(::findDeceptiveLinks).orEmpty()
    }

    /**
     * The links whose text is a web address, with that address; see [findAddressTexts].
     */
    private val addressTexts: Map<String, String> by lazy {
        displayedHtml?.let(::findAddressTexts).orEmpty()
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
     * Opens a link from the displayed message through [LinkConfirmation], telling it when the link's text named a
     * different site from the one it opens.
     */
    fun confirmAndOpen(context: Context, uri: Uri) {
        val url = uri.toString()
        val shownSite = hostOf(url)?.let { host -> deceptiveLinks[host] }
        linkConfirmation.confirmAndOpen(
            context = context,
            uri = uri,
            shownSite = shownSite,
            textAddress = addressTextFor(url),
            senderAddress = senderAddress,
        )
    }

    /**
     * The WebView hands over the link as it resolved it, which can differ from the `href` as written by a trailing
     * slash or entity decoding; both forms are tried.
     */
    private fun addressTextFor(url: String): String? =
        addressTexts[url] ?: addressTexts[url.removeSuffix("/")] ?: addressTexts["$url/"]

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
