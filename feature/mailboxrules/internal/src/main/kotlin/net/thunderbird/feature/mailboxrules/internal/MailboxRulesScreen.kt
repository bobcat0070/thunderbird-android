package net.thunderbird.feature.mailboxrules.internal

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.collections.immutable.toPersistentList
import net.thunderbird.components.ui.bolt.atom.DividerHorizontal
import net.thunderbird.components.ui.bolt.atom.button.ButtonFilled
import net.thunderbird.components.ui.bolt.atom.button.ButtonSegmentedSingleChoice
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge
import net.thunderbird.components.ui.bolt.atom.text.TextBodyMedium
import net.thunderbird.components.ui.bolt.atom.text.TextTitleMedium
import net.thunderbird.components.ui.bolt.molecule.LoadingView
import net.thunderbird.components.ui.bolt.organism.TopAppBarWithBackButton
import net.thunderbird.components.ui.bolt.template.Scaffold
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import net.thunderbird.core.ui.contract.mvi.observe
import net.thunderbird.feature.mailboxrules.MailboxRule
import net.thunderbird.feature.mailboxrules.internal.MailboxRulesContract.Effect
import net.thunderbird.feature.mailboxrules.internal.MailboxRulesContract.Event
import net.thunderbird.feature.mailboxrules.internal.MailboxRulesContract.Phase
import net.thunderbird.feature.mailboxrules.internal.MailboxRulesContract.State
import net.thunderbird.feature.mailboxrules.internal.MailboxRulesContract.ViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun MailboxRulesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ViewModel = koinViewModel<MailboxRulesViewModel>(),
) {
    val context = LocalContext.current
    val resources = context.resources
    val signInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.event(Event.SignInFinished(result.data))
    }

    val (state, dispatch) = viewModel.observe { effect ->
        when (effect) {
            Effect.NavigateBack -> onBack()

            is Effect.StartSignIn -> signInLauncher.launch(effect.intent)

            Effect.SignInUnavailable -> Toast.makeText(
                context,
                resources.getString(R.string.mailboxrules_sign_in_unavailable),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    LaunchedEffect(Unit) {
        dispatch(Event.LoadAccounts)
    }

    BackHandler {
        dispatch(Event.BackClicked)
    }

    Scaffold(
        topBar = {
            TopAppBarWithBackButton(
                title = stringResource(R.string.mailboxrules_title),
                onBackClick = { dispatch(Event.BackClicked) },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        MailboxRulesContent(state = state.value, onEvent = dispatch, contentPadding = innerPadding)
    }
}

@Composable
private fun MailboxRulesContent(
    state: State,
    onEvent: (Event) -> Unit,
    contentPadding: PaddingValues,
) {
    val account = state.account
    if (account == null) {
        TextBodyLarge(
            text = stringResource(R.string.mailboxrules_no_accounts),
            modifier = Modifier.padding(contentPadding).padding(BoltTheme.spacings.double),
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "intro") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(BoltTheme.spacings.double),
            ) {
                if (state.accounts.size > 1) {
                    ButtonSegmentedSingleChoice(
                        onClick = { onEvent(Event.AccountClicked(it)) },
                        options = state.accounts.toPersistentList(),
                        optionTitle = { it.email },
                        selectedOption = account,
                        modifier = Modifier.fillMaxWidth().padding(bottom = BoltTheme.spacings.default),
                    )
                }
                TextBodyMedium(text = stringResource(R.string.mailboxrules_intro, account.email))
                ButtonFilled(
                    text = stringResource(R.string.mailboxrules_check),
                    onClick = { onEvent(Event.CheckClicked) },
                    enabled = state.phase != Phase.Working,
                    modifier = Modifier.padding(top = BoltTheme.spacings.default),
                )
            }
            DividerHorizontal()
        }

        resultItems(phase = state.phase, mailboxAddress = account.email)
    }
}

private fun LazyListScope.resultItems(phase: Phase, mailboxAddress: String) {
    when (phase) {
        Phase.Ready -> Unit

        Phase.Working -> item(key = "working") {
            LoadingView(modifier = Modifier.fillMaxWidth().padding(BoltTheme.spacings.double))
        }

        is Phase.Refused -> item(key = "refused") {
            Message(stringResource(R.string.mailboxrules_refused))
        }

        is Phase.Failed -> item(key = "failed") {
            Message(stringResource(R.string.mailboxrules_failed))
        }

        is Phase.Loaded -> {
            val needLook = phase.rules.count { it.isEnabled && it.concerns(mailboxAddress).isNotEmpty() }
            item(key = "summary") {
                Message(
                    when {
                        phase.rules.isEmpty() -> stringResource(R.string.mailboxrules_none)
                        needLook == 0 -> stringResource(R.string.mailboxrules_all_fine)
                        else -> pluralStringResource(R.plurals.mailboxrules_need_look, needLook, needLook)
                    },
                )
            }
            items(phase.rules, key = { "${it.name}-${phase.rules.indexOf(it)}" }) { rule ->
                RuleRow(rule = rule, mailboxAddress = mailboxAddress)
                DividerHorizontal()
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    TextBodyLarge(text = text, modifier = Modifier.padding(BoltTheme.spacings.double))
}

@Composable
private fun RuleRow(rule: MailboxRule, mailboxAddress: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BoltTheme.spacings.double, vertical = BoltTheme.spacings.default),
    ) {
        TextTitleMedium(text = rule.name.ifBlank { stringResource(R.string.mailboxrules_unnamed) })
        if (!rule.isEnabled) {
            TextBodyMedium(text = stringResource(R.string.mailboxrules_off))
        }
        rule.concerns(mailboxAddress).forEach { concern ->
            TextBodyMedium(
                text = when (concern) {
                    is RuleConcern.ForwardsOutside -> stringResource(
                        R.string.mailboxrules_forwards_outside,
                        concern.addresses.joinToString(", "),
                    )

                    RuleConcern.Deletes -> stringResource(R.string.mailboxrules_deletes)

                    RuleConcern.Hides -> stringResource(R.string.mailboxrules_hides)
                },
                color = if (rule.isEnabled) BoltTheme.colors.error else Color.Unspecified,
            )
        }
        val inside = rule.forwardsTo.filter { address ->
            rule.concerns(mailboxAddress).none { it is RuleConcern.ForwardsOutside && address in it.addresses }
        }
        if (inside.isNotEmpty()) {
            TextBodyMedium(text = stringResource(R.string.mailboxrules_forwards_inside, inside.joinToString(", ")))
        }
    }
}
