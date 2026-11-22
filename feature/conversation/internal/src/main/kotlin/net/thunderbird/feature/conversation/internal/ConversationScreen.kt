package net.thunderbird.feature.conversation.internal

import android.graphics.Color
import android.text.format.DateUtils
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import net.thunderbird.components.ui.bolt.atom.DividerHorizontal
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.icon.Icon
import net.thunderbird.components.ui.bolt.atom.icon.Icons
import net.thunderbird.components.ui.bolt.atom.text.TextBodyMedium
import net.thunderbird.components.ui.bolt.atom.text.TextTitleLarge
import net.thunderbird.components.ui.bolt.atom.text.TextTitleMedium
import net.thunderbird.components.ui.bolt.molecule.LoadingView
import net.thunderbird.components.ui.bolt.organism.TopAppBarWithBackButton
import net.thunderbird.components.ui.bolt.template.Scaffold
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import net.thunderbird.core.ui.contract.mvi.observe
import net.thunderbird.feature.conversation.ConversationMessage
import net.thunderbird.feature.conversation.internal.ConversationContract.Body
import net.thunderbird.feature.conversation.internal.ConversationContract.Effect
import net.thunderbird.feature.conversation.internal.ConversationContract.Event
import net.thunderbird.feature.conversation.internal.ConversationContract.State
import net.thunderbird.feature.conversation.internal.ConversationContract.ViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun ConversationScreen(
    accountId: String,
    threadRoot: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ViewModel = koinViewModel<ConversationViewModel>(),
) {
    val (state, dispatch) = viewModel.observe { effect ->
        when (effect) {
            Effect.NavigateBack -> onBack()
        }
    }

    LaunchedEffect(Unit) {
        dispatch(Event.LoadConversation(accountId, threadRoot))
    }

    BackHandler {
        dispatch(Event.BackClicked)
    }

    Scaffold(
        topBar = {
            TopAppBarWithBackButton(
                title = stringResource(R.string.conversation_title),
                onBackClick = { dispatch(Event.BackClicked) },
                actions = {
                    ButtonText(
                        text = stringResource(R.string.conversation_show_as_list),
                        onClick = { dispatch(Event.ShowAsListClicked) },
                    )
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        ConversationContent(state = state.value, onEvent = dispatch, contentPadding = innerPadding)
    }
}

@Composable
private fun ConversationContent(
    state: State,
    onEvent: (Event) -> Unit,
    contentPadding: PaddingValues,
) {
    if (state.isLoading) {
        LoadingView(modifier = Modifier.padding(contentPadding).fillMaxSize())
        return
    }

    // A column rather than a lazy list: a thread is a handful of messages, and each open one holds a web view that
    // a lazy list would throw away and load again every time it scrolled out of sight.
    Column(
        modifier = Modifier
            .padding(contentPadding)
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        state.subject?.takeIf { it.isNotBlank() }?.let { subject ->
            TextTitleLarge(
                text = subject,
                modifier = Modifier.padding(BoltTheme.spacings.double),
            )
        }
        DividerHorizontal()

        state.messages.forEach { message ->
            MessageCard(
                message = message,
                isExpanded = message.key in state.expanded,
                body = state.bodies[message.key],
                onEvent = onEvent,
            )
            DividerHorizontal()
        }
    }
}

@Composable
private fun MessageCard(
    message: ConversationMessage,
    isExpanded: Boolean,
    body: Body?,
    onEvent: (Event) -> Unit,
) {
    val context = LocalContext.current
    val date = DateUtils.formatDateTime(
        context,
        message.date,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEvent(Event.MessageClicked(message)) }
                .padding(horizontal = BoltTheme.spacings.double, vertical = BoltTheme.spacings.default),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                TextTitleMedium(text = message.senderName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextBodyMedium(
                    text = if (isExpanded) date else "$date · ${message.preview}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (message.hasWarning) {
                Icon(imageVector = Icons.Filled.Warning, tint = BoltTheme.colors.error)
            }
            if (message.hasAttachments) {
                Icon(imageVector = Icons.Outlined.Attachment)
            }
        }

        if (isExpanded) {
            ExpandedMessage(message = message, body = body, onEvent = onEvent)
        }
    }
}

@Composable
private fun ExpandedMessage(
    message: ConversationMessage,
    body: Body?,
    onEvent: (Event) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (message.hasWarning) {
            TextBodyMedium(
                text = stringResource(R.string.conversation_warning),
                color = BoltTheme.colors.error,
                modifier = Modifier.padding(horizontal = BoltTheme.spacings.double),
            )
        }

        when (body) {
            null, Body.Loading -> LoadingView(modifier = Modifier.fillMaxWidth().padding(BoltTheme.spacings.double))

            Body.Failed -> TextBodyMedium(
                text = stringResource(R.string.conversation_body_failed),
                modifier = Modifier.padding(BoltTheme.spacings.double),
            )

            is Body.Html -> MessageWebView(
                html = body.html,
                onLinkClick = { onEvent(Event.LinkClicked(message, it)) },
            )
        }

        Row(
            modifier = Modifier.padding(horizontal = BoltTheme.spacings.default),
            horizontalArrangement = Arrangement.spacedBy(BoltTheme.spacings.half),
        ) {
            ButtonText(
                text = stringResource(R.string.conversation_reply),
                onClick = { onEvent(Event.ReplyClicked(message, toAll = false)) },
            )
            ButtonText(
                text = stringResource(R.string.conversation_reply_all),
                onClick = { onEvent(Event.ReplyClicked(message, toAll = true)) },
            )
            ButtonText(
                text = stringResource(R.string.conversation_forward),
                onClick = { onEvent(Event.ForwardClicked(message)) },
            )
            ButtonText(
                text = stringResource(R.string.conversation_open),
                onClick = { onEvent(Event.OpenClicked(message)) },
            )
        }
    }
}

/**
 * A message's text, shown as the message view shows it but with nothing fetched from the network - no pictures
 * from the sender's server, no trackers - and no scripts. A link goes to [onLinkClick], for the link check.
 * The whole message is on [Open]; this is for reading along.
 */
@Composable
private fun MessageWebView(html: String, onLinkClick: (String) -> Unit) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                // Drawn inside a scrolling column, an opaque web view paints its white over the whole column, hiding
                // the messages above it; transparent, it paints only its own text on the card.
                setBackgroundColor(Color.TRANSPARENT)
                // As the message view: no layer of its own, which a long message could make too tall for the GPU.
                setLayerType(View.LAYER_TYPE_NONE, null)
                overScrollMode = View.OVER_SCROLL_NEVER
                settings.javaScriptEnabled = false
                settings.blockNetworkLoads = true
                settings.blockNetworkImage = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        onLinkClick(request.url.toString())
                        return true
                    }
                }
            }
        },
        update = { webView ->
            // Loaded once: loading again on every recomposition would flash the text and lose the scroll.
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = BoltTheme.spacings.default),
    )
}
