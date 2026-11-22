package net.thunderbird.feature.conversation

import kotlinx.serialization.Serializable
import net.thunderbird.core.ui.navigation.Navigation
import net.thunderbird.core.ui.navigation.Route

const val CONVERSATION_BASE_DEEP_LINK = "app://feature/conversation"

sealed interface ConversationRoute : Route {
    /**
     * A thread of one account read as one conversation, oldest message first.
     *
     * @param threadRoot the database id of the thread's root, as the message list knows it.
     */
    @Serializable
    data class Thread(val accountId: String, val threadRoot: Long) : ConversationRoute {
        override val basePath: String = BASE_PATH

        override fun route(): String = "$basePath/$accountId/$threadRoot"

        companion object {
            const val BASE_PATH = "$CONVERSATION_BASE_DEEP_LINK/thread"
        }
    }
}

interface ConversationNavigation : Navigation<ConversationRoute>

/**
 * One message of a conversation, as much of it as its card shows before it is opened.
 *
 * @param key what the app needs to find the message again; opaque to the screen.
 * @param hasWarning whether the message view would warn about its sender: a lookalike, a blocklisted domain, a plus
 *   address used by someone it was not given to.
 */
data class ConversationMessage(
    val key: String,
    val senderName: String,
    val senderAddress: String?,
    val date: Long,
    val subject: String?,
    val preview: String,
    val isRead: Boolean,
    val hasAttachments: Boolean,
    val hasWarning: Boolean,
)

/**
 * What showing a message's text came to.
 */
sealed interface MessageBody {
    /**
     * @param html the message as the message view shows it, already made safe to display.
     */
    data class Html(val html: String) : MessageBody

    /** The message could not be downloaded or read. */
    data object Failed : MessageBody
}

/**
 * Reads a thread and acts on its messages.
 */
interface ConversationSource {
    /**
     * @return the thread's messages, oldest first; empty when the thread is gone.
     */
    suspend fun messages(accountId: String, threadRoot: Long): List<ConversationMessage>

    /**
     * The message's text, downloaded first when only its envelope is on the device.
     */
    suspend fun body(key: String): MessageBody

    fun markRead(keys: List<String>)

    fun reply(key: String, toAll: Boolean)

    fun forward(key: String)

    /**
     * Opens the message on its own, where everything the message view says about it is shown.
     */
    fun openMessage(key: String)

    /**
     * Opens a link from a message through the link check, as a link in the message view is.
     */
    fun openLink(url: String, senderAddress: String?)

    /**
     * Shows the thread as a list of its messages, as threads opened before conversations.
     */
    fun showAsList(accountId: String, threadRoot: Long)
}
