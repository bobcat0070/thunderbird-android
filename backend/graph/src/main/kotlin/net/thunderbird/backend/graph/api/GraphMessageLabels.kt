package net.thunderbird.backend.graph.api

import com.fsck.k9.mail.Message
import com.fsck.k9.mail.MessageImportance
import com.fsck.k9.mail.setImportance

internal const val GRAPH_IMPORTANCE_LOW = "low"
internal const val GRAPH_IMPORTANCE_NORMAL = "normal"
internal const val GRAPH_IMPORTANCE_HIGH = "high"

/**
 * Records the importance Graph reports for a message in the headers the app reads it from.
 *
 * Graph's value is the mailbox's own, so it replaces what the headers said. A value Graph did not send leaves the
 * headers as they are.
 */
internal fun Message.setServerImportance(importance: String?) {
    val serverImportance = when (importance?.lowercase()) {
        GRAPH_IMPORTANCE_HIGH -> MessageImportance.HIGH
        GRAPH_IMPORTANCE_LOW -> MessageImportance.LOW
        GRAPH_IMPORTANCE_NORMAL -> MessageImportance.NORMAL
        else -> null
    }

    serverImportance?.let { setImportance(it) }
}

/**
 * The Outlook categories of a message, as the app stores them: trimmed, without blanks or repeats.
 *
 * @return `null` when Graph did not say which categories the message has.
 */
internal fun GraphMessage.serverCategories(): List<String>? {
    return categories?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()
}
