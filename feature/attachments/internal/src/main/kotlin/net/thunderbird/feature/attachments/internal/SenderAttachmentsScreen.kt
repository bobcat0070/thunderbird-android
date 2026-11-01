package net.thunderbird.feature.attachments.internal

import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import net.thunderbird.components.ui.bolt.atom.DividerHorizontal
import net.thunderbird.components.ui.bolt.atom.button.ButtonIcon
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.icon.Icon
import net.thunderbird.components.ui.bolt.atom.icon.Icons
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge
import net.thunderbird.components.ui.bolt.atom.text.TextBodyMedium
import net.thunderbird.components.ui.bolt.atom.text.TextTitleMedium
import net.thunderbird.components.ui.bolt.molecule.LoadingView
import net.thunderbird.components.ui.bolt.organism.TopAppBarWithBackButton
import net.thunderbird.components.ui.bolt.template.Scaffold
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import net.thunderbird.core.ui.contract.mvi.observe
import net.thunderbird.feature.attachments.StoredAttachment
import net.thunderbird.feature.attachments.internal.SenderAttachmentsContract.Effect
import net.thunderbird.feature.attachments.internal.SenderAttachmentsContract.Event
import net.thunderbird.feature.attachments.internal.SenderAttachmentsContract.Notice
import net.thunderbird.feature.attachments.internal.SenderAttachmentsContract.State
import net.thunderbird.feature.attachments.internal.SenderAttachmentsContract.ViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun SenderAttachmentsScreen(
    address: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ViewModel = koinViewModel<SenderAttachmentsViewModel>(),
) {
    val context = LocalContext.current
    val resources = context.resources

    val (state, dispatch) = viewModel.observe { effect ->
        when (effect) {
            Effect.NavigateBack -> onBack()

            is Effect.ShowNotice -> {
                val text = when (val notice = effect.notice) {
                    Notice.NoViewer -> resources.getString(R.string.attachments_no_viewer)

                    Notice.OpenFailed -> resources.getString(R.string.attachments_open_failed)

                    is Notice.Downloaded -> resources.getQuantityString(
                        R.plurals.attachments_downloaded,
                        notice.count,
                        notice.count,
                    )
                }
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        dispatch(Event.LoadAttachments(address))
    }

    BackHandler {
        dispatch(Event.BackClicked)
    }

    Scaffold(
        topBar = {
            TopAppBarWithBackButton(
                title = stringResource(R.string.attachments_sender_title),
                onBackClick = { dispatch(Event.BackClicked) },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        SenderAttachmentsContent(
            state = state.value,
            onEvent = dispatch,
            contentPadding = innerPadding,
        )
    }
}

@Composable
private fun SenderAttachmentsContent(
    state: State,
    onEvent: (Event) -> Unit,
    contentPadding: PaddingValues,
) {
    if (state.isLoading) {
        LoadingView(modifier = Modifier.padding(contentPadding).fillMaxSize())
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "sender") {
            TextBodyMedium(
                text = stringResource(R.string.attachments_sender_subtitle, state.address),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    horizontal = BoltTheme.spacings.double,
                    vertical = BoltTheme.spacings.default,
                ),
            )
        }

        if (state.messagesNotDownloaded > 0) {
            item(key = "not_downloaded") {
                NotDownloadedNotice(state = state, onEvent = onEvent)
                DividerHorizontal()
            }
        }

        if (state.attachments.isEmpty()) {
            item(key = "empty") {
                TextBodyLarge(
                    text = stringResource(R.string.attachments_sender_empty),
                    modifier = Modifier.padding(BoltTheme.spacings.double),
                )
            }
        }

        items(state.attachments, key = { it.key }) { attachment ->
            AttachmentRow(
                attachment = attachment,
                isOpening = attachment.key == state.openingKey,
                onEvent = onEvent,
            )
            DividerHorizontal()
        }
    }
}

@Composable
private fun NotDownloadedNotice(
    state: State,
    onEvent: (Event) -> Unit,
) {
    val count = state.messagesNotDownloaded
    val batch = minOf(count, DOWNLOAD_BATCH_SIZE)
    val download = state.download

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BoltTheme.spacings.double, vertical = BoltTheme.spacings.default),
    ) {
        TextBodyMedium(text = pluralStringResource(R.plurals.attachments_messages_not_downloaded, count, count))
        ButtonText(
            text = if (download != null && download.total > 0) {
                stringResource(R.string.attachments_downloading, download.done, download.total)
            } else {
                pluralStringResource(R.plurals.attachments_download_messages, batch, batch)
            },
            onClick = { onEvent(Event.DownloadMessagesClicked) },
            enabled = download == null,
        )
    }
}

@Composable
private fun AttachmentRow(
    attachment: StoredAttachment,
    isOpening: Boolean,
    onEvent: (Event) -> Unit,
) {
    val context = LocalContext.current
    val date = DateUtils.formatDateTime(
        context,
        attachment.date,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
    )
    val size = attachment.size
    val details = when {
        isOpening -> stringResource(R.string.attachments_opening)

        size != null -> stringResource(
            R.string.attachments_size_and_date,
            Formatter.formatShortFileSize(context, size),
            date,
        )

        else -> date
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isOpening) { onEvent(Event.AttachmentClicked(attachment)) }
            .padding(
                start = BoltTheme.spacings.double,
                top = BoltTheme.spacings.default,
                bottom = BoltTheme.spacings.default,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BoltTheme.spacings.double),
    ) {
        Icon(
            imageVector = if (attachment.mimeType?.startsWith("image/") == true) {
                Icons.Outlined.Image
            } else {
                Icons.Outlined.Description
            },
        )

        Column(modifier = Modifier.weight(1f)) {
            TextTitleMedium(text = attachment.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextBodyMedium(text = details, maxLines = 1)
            attachment.subject?.takeIf { it.isNotBlank() }?.let { subject ->
                TextBodyMedium(text = subject, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        ButtonIcon(
            onClick = { onEvent(Event.ShowMessageClicked(attachment)) },
            imageVector = Icons.Outlined.Drafts,
            contentDescription = stringResource(R.string.attachments_show_message),
        )
    }
}
