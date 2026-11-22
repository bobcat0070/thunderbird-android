package net.thunderbird.feature.conversation.internal

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import net.thunderbird.core.ui.contract.mvi.BaseViewModel
import net.thunderbird.feature.conversation.ConversationMessage
import net.thunderbird.feature.conversation.ConversationSource
import net.thunderbird.feature.conversation.MessageBody
import net.thunderbird.feature.conversation.internal.ConversationContract.Body
import net.thunderbird.feature.conversation.internal.ConversationContract.Effect
import net.thunderbird.feature.conversation.internal.ConversationContract.Event
import net.thunderbird.feature.conversation.internal.ConversationContract.State

/**
 * Shows a thread as one conversation: the messages the reader has not read, and the newest, open with their text;
 * the rest as a line each, opened at a tap. A message shown open counts as read.
 */
internal class ConversationViewModel(
    private val source: ConversationSource,
) : BaseViewModel<State, Event, Effect>(State()), ConversationContract.ViewModel {

    override fun event(event: Event) {
        when (event) {
            is Event.LoadConversation -> handleOneTimeEvent(event) { load(event.accountId, event.threadRoot) }
            is Event.MessageClicked -> toggle(event.message)
            is Event.ReplyClicked -> source.reply(event.message.key, event.toAll)
            is Event.ForwardClicked -> source.forward(event.message.key)
            is Event.OpenClicked -> source.openMessage(event.message.key)
            is Event.LinkClicked -> source.openLink(event.url, event.message.senderAddress)
            Event.ShowAsListClicked -> source.showAsList(state.value.accountId, state.value.threadRoot)
            Event.BackClicked -> emitEffect(Effect.NavigateBack)
        }
    }

    private fun load(accountId: String, threadRoot: Long) {
        updateState { it.copy(accountId = accountId, threadRoot = threadRoot) }

        viewModelScope.launch {
            val messages = source.messages(accountId, threadRoot)
            val open = initiallyOpen(messages)
            val openKeys = open.mapTo(mutableSetOf()) { it.key }
            updateState { it.copy(isLoading = false, messages = messages, expanded = openKeys) }
            if (messages.isEmpty()) {
                emitEffect(Effect.NavigateBack)
                return@launch
            }
            markRead(open)
            open.forEach { loadBody(it) }
        }
    }

    private fun toggle(message: ConversationMessage) {
        if (message.key in state.value.expanded) {
            updateState { it.copy(expanded = it.expanded - message.key) }
            return
        }

        updateState { it.copy(expanded = it.expanded + message.key) }
        markRead(listOf(message))
        if (message.key !in state.value.bodies) {
            viewModelScope.launch { loadBody(message) }
        }
    }

    private fun markRead(messages: List<ConversationMessage>) {
        val unread = messages.filterNot { it.isRead }.map { it.key }.toSet()
        if (unread.isEmpty()) return

        source.markRead(unread.toList())
        updateState { state ->
            state.copy(messages = state.messages.map { if (it.key in unread) it.copy(isRead = true) else it })
        }
    }

    private suspend fun loadBody(message: ConversationMessage) {
        updateState { it.copy(bodies = it.bodies + (message.key to Body.Loading)) }
        val body = when (val result = source.body(message.key)) {
            is MessageBody.Html -> Body.Html(result.html)
            MessageBody.Failed -> Body.Failed
        }
        updateState { it.copy(bodies = it.bodies + (message.key to body)) }
    }
}

/**
 * The messages shown open when the conversation opens: every unread one, and the newest, which is what the reader
 * came for.
 */
internal fun initiallyOpen(messages: List<ConversationMessage>): List<ConversationMessage> {
    val newest = messages.lastOrNull()
    return messages.filter { !it.isRead || it == newest }
}
