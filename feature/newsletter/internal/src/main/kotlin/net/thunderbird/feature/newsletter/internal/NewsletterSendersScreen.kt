package net.thunderbird.feature.newsletter.internal

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
import net.thunderbird.components.ui.bolt.atom.icon.Icons
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge
import net.thunderbird.components.ui.bolt.atom.text.TextBodyMedium
import net.thunderbird.components.ui.bolt.atom.text.TextTitleMedium
import net.thunderbird.components.ui.bolt.molecule.LoadingView
import net.thunderbird.components.ui.bolt.organism.AlertDialog
import net.thunderbird.components.ui.bolt.organism.TopAppBarWithBackButton
import net.thunderbird.components.ui.bolt.template.Scaffold
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import net.thunderbird.core.ui.contract.mvi.observe
import net.thunderbird.feature.newsletter.NewsletterSender
import net.thunderbird.feature.newsletter.internal.NewsletterSendersContract.Action
import net.thunderbird.feature.newsletter.internal.NewsletterSendersContract.Confirmation
import net.thunderbird.feature.newsletter.internal.NewsletterSendersContract.Effect
import net.thunderbird.feature.newsletter.internal.NewsletterSendersContract.Event
import net.thunderbird.feature.newsletter.internal.NewsletterSendersContract.Notice
import net.thunderbird.feature.newsletter.internal.NewsletterSendersContract.State
import net.thunderbird.feature.newsletter.internal.NewsletterSendersContract.ViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun NewsletterSendersScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ViewModel = koinViewModel<NewsletterSendersViewModel>(),
) {
    val context = LocalContext.current
    val resources = context.resources

    val (state, dispatch) = viewModel.observe { effect ->
        when (effect) {
            Effect.NavigateBack -> onBack()

            is Effect.ShowNotice -> {
                val text = when (val notice = effect.notice) {
                    is Notice.Unsubscribed -> resources.getString(
                        R.string.newsletter_unsubscribed,
                        notice.sender.displayName(),
                    )

                    Notice.UnsubscribeUnavailable -> resources.getString(R.string.newsletter_unsubscribe_unavailable)

                    is Notice.Archived -> resources.getQuantityString(
                        R.plurals.newsletter_archived,
                        notice.count,
                        notice.count,
                    )

                    is Notice.Deleted -> resources.getQuantityString(
                        R.plurals.newsletter_deleted,
                        notice.count,
                        notice.count,
                    )
                }
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        dispatch(Event.LoadSenders)
    }

    BackHandler {
        dispatch(Event.BackClicked)
    }

    Scaffold(
        topBar = {
            TopAppBarWithBackButton(
                title = stringResource(R.string.newsletter_senders_title),
                onBackClick = { dispatch(Event.BackClicked) },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        NewsletterSendersContent(
            state = state.value,
            onEvent = dispatch,
            contentPadding = innerPadding,
        )
    }

    state.value.confirmation?.let { confirmation ->
        ConfirmationDialog(
            confirmation = confirmation,
            onConfirm = { dispatch(Event.ConfirmClicked) },
            onDismiss = { dispatch(Event.DismissClicked) },
        )
    }
}

@Composable
private fun NewsletterSendersContent(
    state: State,
    onEvent: (Event) -> Unit,
    contentPadding: PaddingValues,
) {
    when {
        state.isLoading -> LoadingView(modifier = Modifier.padding(contentPadding).fillMaxSize())

        state.senders.isEmpty() -> TextBodyLarge(
            text = stringResource(R.string.newsletter_senders_empty),
            modifier = Modifier.padding(contentPadding).padding(BoltTheme.spacings.double),
        )

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            items(state.senders, key = { it.address }) { sender ->
                SenderRow(
                    sender = sender,
                    isBusy = sender.address == state.busyAddress,
                    onEvent = onEvent,
                )
                DividerHorizontal()
            }
        }
    }
}

@Composable
private fun SenderRow(
    sender: NewsletterSender,
    isBusy: Boolean,
    onEvent: (Event) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEvent(Event.SenderClicked(sender)) }
            .padding(
                start = BoltTheme.spacings.double,
                top = BoltTheme.spacings.default,
                bottom = BoltTheme.spacings.default,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            TextTitleMedium(text = sender.displayName(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sender.name != null) {
                TextBodyMedium(text = sender.address, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextBodyMedium(
                text = pluralStringResource(
                    R.plurals.newsletter_message_count,
                    sender.messageCount,
                    sender.messageCount,
                ),
            )
        }

        if (sender.canUnsubscribe) {
            ButtonText(
                text = stringResource(R.string.newsletter_unsubscribe),
                onClick = { onEvent(Event.ActionClicked(sender, Action.UNSUBSCRIBE)) },
                enabled = !isBusy,
            )
        }
        ButtonIcon(
            onClick = { onEvent(Event.ActionClicked(sender, Action.ARCHIVE_ALL)) },
            imageVector = Icons.Outlined.Archive,
            enabled = !isBusy,
            contentDescription = stringResource(R.string.newsletter_archive_all),
        )
        ButtonIcon(
            onClick = { onEvent(Event.ActionClicked(sender, Action.DELETE_ALL)) },
            imageVector = Icons.Outlined.Delete,
            enabled = !isBusy,
            contentDescription = stringResource(R.string.newsletter_delete_all),
        )
    }
}

@Composable
private fun ConfirmationDialog(
    confirmation: Confirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sender = confirmation.sender
    val count = sender.messageCount
    val (title, text) = when (confirmation.action) {
        Action.UNSUBSCRIBE -> stringResource(R.string.newsletter_unsubscribe) to
            stringResource(R.string.newsletter_unsubscribe_confirm, sender.displayName())

        Action.ARCHIVE_ALL -> stringResource(R.string.newsletter_archive_all) to
            pluralStringResource(R.plurals.newsletter_archive_all_confirm, count, count, sender.displayName())

        Action.DELETE_ALL -> stringResource(R.string.newsletter_delete_all) to
            pluralStringResource(R.plurals.newsletter_delete_all_confirm, count, count, sender.displayName())
    }

    AlertDialog(
        title = title,
        text = text,
        confirmText = title,
        onConfirmClick = onConfirm,
        onDismissRequest = onDismiss,
        dismissText = stringResource(R.string.newsletter_cancel),
        onDismissClick = onDismiss,
    )
}

private fun NewsletterSender.displayName(): String = name?.takeIf { it.isNotBlank() } ?: address
