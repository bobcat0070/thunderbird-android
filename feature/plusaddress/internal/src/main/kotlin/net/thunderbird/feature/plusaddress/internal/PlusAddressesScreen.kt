package net.thunderbird.feature.plusaddress.internal

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import net.thunderbird.components.ui.bolt.atom.DividerHorizontal
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.icon.Icon
import net.thunderbird.components.ui.bolt.atom.icon.Icons
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge
import net.thunderbird.components.ui.bolt.atom.text.TextBodyMedium
import net.thunderbird.components.ui.bolt.atom.text.TextBodySmall
import net.thunderbird.components.ui.bolt.molecule.LoadingView
import net.thunderbird.components.ui.bolt.organism.TopAppBarWithBackButton
import net.thunderbird.components.ui.bolt.template.Scaffold
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import net.thunderbird.core.ui.contract.mvi.observe
import net.thunderbird.feature.plusaddress.internal.PlusAddressesContract.Effect
import net.thunderbird.feature.plusaddress.internal.PlusAddressesContract.Event
import net.thunderbird.feature.plusaddress.internal.PlusAddressesContract.State
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun PlusAddressesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlusAddressesContract.ViewModel = koinViewModel<PlusAddressesViewModel>(),
) {
    val (state, dispatch) = viewModel.observe { effect ->
        when (effect) {
            Effect.NavigateBack -> onBack()
        }
    }

    LaunchedEffect(Unit) {
        dispatch(Event.Load)
    }

    BackHandler {
        dispatch(Event.BackClicked)
    }

    Scaffold(
        topBar = {
            TopAppBarWithBackButton(
                title = stringResource(R.string.plusaddress_title),
                onBackClick = { dispatch(Event.BackClicked) },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        if (state.value.isLoading) {
            LoadingView(modifier = Modifier.padding(innerPadding).fillMaxSize())
        } else {
            PlusAddressList(state = state.value, onEvent = dispatch, modifier = Modifier.padding(innerPadding))
        }
    }
}

@Composable
private fun PlusAddressList(
    state: State,
    onEvent: (Event) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = BoltTheme.spacings.double),
    ) {
        item(key = "intro") {
            TextBodyMedium(
                text = stringResource(
                    if (state.records.isEmpty()) R.string.plusaddress_empty else R.string.plusaddress_intro,
                ),
                modifier = Modifier.padding(BoltTheme.spacings.double),
            )
            DividerHorizontal()
        }
        items(state.records, key = { it.key }) { record ->
            PlusAddressRow(record = record, onEvent = onEvent)
            DividerHorizontal()
        }
    }
}

@Composable
private fun PlusAddressRow(
    record: PlusAddressRecord,
    onEvent: (Event) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BoltTheme.spacings.double, vertical = BoltTheme.spacings.default),
        verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.half),
    ) {
        TextBodyLarge(text = record.address)
        TextBodySmall(text = givenToText(record))
        TextBodySmall(
            text = pluralStringResource(R.plurals.plusaddress_message_count, record.messageCount, record.messageCount),
        )

        if (!record.isIgnored) {
            for (sender in record.otherSenders) {
                OtherSenderRow(record = record, sender = sender, onEvent = onEvent)
            }
        }

        ButtonText(
            text = stringResource(
                if (record.isIgnored) R.string.plusaddress_check_again else R.string.plusaddress_stop_checking,
            ),
            onClick = { onEvent(Event.IgnoreClicked(record.key, isIgnored = !record.isIgnored)) },
        )
    }
}

@Composable
private fun givenToText(record: PlusAddressRecord): String {
    val givenTo = record.givenTo.sorted().joinToString(", ")
    return when {
        record.isIgnored -> stringResource(R.string.plusaddress_not_checked)
        givenTo.isEmpty() -> stringResource(R.string.plusaddress_owner_unknown)
        record.ownerSource == OwnerSource.TAG -> stringResource(R.string.plusaddress_given_to_by_tag, givenTo)
        record.ownerSource == OwnerSource.READER -> stringResource(R.string.plusaddress_given_to_by_reader, givenTo)
        else -> stringResource(R.string.plusaddress_given_to_first_verified, givenTo)
    }
}

@Composable
private fun OtherSenderRow(
    record: PlusAddressRecord,
    sender: OtherSender,
    onEvent: (Event) -> Unit,
) {
    val isLeak = record.givenTo.isNotEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (isLeak) {
            Icon(imageVector = Icons.Outlined.Warning, tint = BoltTheme.colors.error)
        }
        TextBodyMedium(
            text = pluralStringResource(
                if (isLeak) R.plurals.plusaddress_also_used_by else R.plurals.plusaddress_used_by,
                sender.messageCount,
                sender.domain,
                sender.messageCount,
            ),
            color = if (isLeak) BoltTheme.colors.error else BoltTheme.colors.onSurface,
            modifier = Modifier.weight(1f).padding(start = BoltTheme.spacings.half),
        )
        ButtonText(
            text = stringResource(
                if (isLeak) R.string.plusaddress_sender_is_fine else R.string.plusaddress_given_to_this_sender,
            ),
            onClick = { onEvent(Event.AllowSenderClicked(record.key, sender.domain)) },
        )
    }
}
