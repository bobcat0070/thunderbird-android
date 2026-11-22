package net.thunderbird.feature.expiredmail.internal

import android.text.format.DateUtils
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
import net.thunderbird.feature.expiredmail.ExpiredKind
import net.thunderbird.feature.expiredmail.ExpiredMail
import net.thunderbird.feature.expiredmail.internal.ExpiredMailContract.Action
import net.thunderbird.feature.expiredmail.internal.ExpiredMailContract.Confirmation
import net.thunderbird.feature.expiredmail.internal.ExpiredMailContract.Effect
import net.thunderbird.feature.expiredmail.internal.ExpiredMailContract.Event
import net.thunderbird.feature.expiredmail.internal.ExpiredMailContract.State
import net.thunderbird.feature.expiredmail.internal.ExpiredMailContract.ViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun ExpiredMailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ViewModel = koinViewModel<ExpiredMailViewModel>(),
) {
    val context = LocalContext.current
    val resources = context.resources

    val (state, dispatch) = viewModel.observe { effect ->
        when (effect) {
            Effect.NavigateBack -> onBack()

            is Effect.ShowNotice -> {
                val text = resources.getQuantityString(
                    if (effect.action ==
                        Action.ARCHIVE
                    ) {
                        R.plurals.expiredmail_archived
                    } else {
                        R.plurals.expiredmail_deleted
                    },
                    effect.count,
                    effect.count,
                )
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        dispatch(Event.LoadMail)
    }

    BackHandler {
        dispatch(Event.BackClicked)
    }

    Scaffold(
        topBar = {
            TopAppBarWithBackButton(
                title = stringResource(R.string.expiredmail_title),
                onBackClick = { dispatch(Event.BackClicked) },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        ExpiredMailContent(state = state.value, onEvent = dispatch, contentPadding = innerPadding)
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
private fun ExpiredMailContent(
    state: State,
    onEvent: (Event) -> Unit,
    contentPadding: PaddingValues,
) {
    when {
        state.isLoading -> LoadingView(modifier = Modifier.padding(contentPadding).fillMaxSize())

        state.mails.isEmpty() -> TextBodyLarge(
            text = stringResource(R.string.expiredmail_empty),
            modifier = Modifier.padding(contentPadding).padding(BoltTheme.spacings.double),
        )

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            state.groups.forEach { (kind, mails) ->
                item(key = "group-$kind") {
                    GroupHeader(kind = kind, count = mails.size, isBusy = state.isBusy, onEvent = onEvent)
                }
                items(mails, key = { it.key }) { mail ->
                    MailRow(mail = mail, isBusy = state.isBusy, onEvent = onEvent)
                    DividerHorizontal()
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(
    kind: ExpiredKind,
    count: Int,
    isBusy: Boolean,
    onEvent: (Event) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = BoltTheme.spacings.double,
                end = BoltTheme.spacings.double,
                top = BoltTheme.spacings.double,
            ),
    ) {
        TextTitleMedium(text = groupTitle(kind, count))
        TextBodyMedium(text = stringResource(groupExplanation(kind)))
        Row(horizontalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default)) {
            ButtonText(
                text = stringResource(R.string.expiredmail_archive_all),
                onClick = { onEvent(Event.GroupActionClicked(kind, Action.ARCHIVE)) },
                enabled = !isBusy,
            )
            ButtonText(
                text = stringResource(R.string.expiredmail_delete_all),
                onClick = { onEvent(Event.GroupActionClicked(kind, Action.DELETE)) },
                enabled = !isBusy,
            )
        }
    }
    DividerHorizontal()
}

@Composable
private fun MailRow(
    mail: ExpiredMail,
    isBusy: Boolean,
    onEvent: (Event) -> Unit,
) {
    val context = LocalContext.current
    val date = DateUtils.formatDateTime(context, mail.date, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEvent(Event.MailClicked(mail)) }
            .padding(
                start = BoltTheme.spacings.double,
                top = BoltTheme.spacings.default,
                bottom = BoltTheme.spacings.default,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            TextTitleMedium(
                text = mail.subject?.takeIf { it.isNotBlank() } ?: stringResource(R.string.expiredmail_no_subject),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextBodyMedium(
                text = listOfNotNull(mail.senderName, date).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ButtonIcon(
            onClick = { onEvent(Event.MailActionClicked(mail, Action.ARCHIVE)) },
            imageVector = Icons.Outlined.Archive,
            enabled = !isBusy,
            contentDescription = stringResource(R.string.expiredmail_archive),
        )
        ButtonIcon(
            onClick = { onEvent(Event.MailActionClicked(mail, Action.DELETE)) },
            imageVector = Icons.Outlined.Delete,
            enabled = !isBusy,
            contentDescription = stringResource(R.string.expiredmail_delete),
        )
    }
}

@Composable
private fun groupTitle(kind: ExpiredKind, count: Int): String = when (kind) {
    ExpiredKind.SIGN_IN -> pluralStringResource(R.plurals.expiredmail_group_sign_in, count, count)
    ExpiredKind.DELIVERED_PARCEL -> pluralStringResource(R.plurals.expiredmail_group_delivered, count, count)
}

private fun groupExplanation(kind: ExpiredKind): Int = when (kind) {
    ExpiredKind.SIGN_IN -> R.string.expiredmail_group_sign_in_explanation
    ExpiredKind.DELIVERED_PARCEL -> R.string.expiredmail_group_delivered_explanation
}

@Composable
private fun ConfirmationDialog(
    confirmation: Confirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val count = confirmation.count
    val (title, text) = when (confirmation.action) {
        Action.ARCHIVE -> stringResource(R.string.expiredmail_archive_all) to
            pluralStringResource(R.plurals.expiredmail_archive_all_confirm, count, count)

        Action.DELETE -> stringResource(R.string.expiredmail_delete_all) to
            pluralStringResource(R.plurals.expiredmail_delete_all_confirm, count, count)
    }

    AlertDialog(
        title = title,
        text = text,
        confirmText = title,
        onConfirmClick = onConfirm,
        onDismissRequest = onDismiss,
        dismissText = stringResource(R.string.expiredmail_cancel),
        onDismissClick = onDismiss,
    )
}
