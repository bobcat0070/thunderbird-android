package net.thunderbird.feature.conversation.internal

import net.thunderbird.core.ui.contract.mvi.UnidirectionalViewModel
import net.thunderbird.feature.conversation.ConversationMessage

internal interface ConversationContract {

    interface ViewModel : UnidirectionalViewModel<State, Event, Effect>

    /**
     * @param messages the thread, oldest first.
     * @param expanded the messages shown open, with their text.
     * @param bodies the text of the messages opened so far.
     */
    data class State(
        val accountId: String = "",
        val threadRoot: Long = 0,
        val isLoading: Boolean = true,
        val messages: List<ConversationMessage> = emptyList(),
        val expanded: Set<String> = emptySet(),
        val bodies: Map<String, Body> = emptyMap(),
    ) {
        val subject: String?
            get() = messages.firstOrNull()?.subject
    }

    sealed interface Body {
        data object Loading : Body
        data class Html(val html: String) : Body
        data object Failed : Body
    }

    sealed interface Event {
        data class LoadConversation(val accountId: String, val threadRoot: Long) : Event
        data class MessageClicked(val message: ConversationMessage) : Event
        data class ReplyClicked(val message: ConversationMessage, val toAll: Boolean) : Event
        data class ForwardClicked(val message: ConversationMessage) : Event
        data class OpenClicked(val message: ConversationMessage) : Event
        data class LinkClicked(val message: ConversationMessage, val url: String) : Event
        data object ShowAsListClicked : Event
        data object BackClicked : Event
    }

    sealed interface Effect {
        data object NavigateBack : Effect
    }
}
