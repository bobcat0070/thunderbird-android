package com.fsck.k9.ui.messagelist

import android.content.res.Resources
import com.fsck.k9.ui.R

/**
 * Where the server search behind a "mail from this sender" list has got to.
 *
 * The list starts with the mail on the device, which is only the newest part of each folder. The search runs by
 * itself when the list opens, and its state is shown under the sender's address so the reader can tell whether
 * what they see is everything, still filling in, or all they will get for now.
 */
internal sealed interface SenderServerSearchStatus {
    /**
     * Not run yet, or stopped before it finished; either way it should run.
     */
    data object NotStarted : SenderServerSearchStatus

    /**
     * @param newMessages the matches found so far that were not already on the device.
     */
    data class Searching(val newMessages: Int) : SenderServerSearchStatus

    data class Finished(val newMessages: Int) : SenderServerSearchStatus

    data object Offline : SenderServerSearchStatus

    data object Failed : SenderServerSearchStatus
}

internal val SenderServerSearchStatus.isRunning: Boolean
    get() = this is SenderServerSearchStatus.Searching

/**
 * A folder's matches have been counted. Only a running search counts them.
 */
internal fun SenderServerSearchStatus.withFolderSearched(newMessages: Int): SenderServerSearchStatus =
    if (this is SenderServerSearchStatus.Searching) copy(newMessages = this.newMessages + newMessages) else this

/**
 * The search has ended. It always reports an end, even after a failure, and the failure is what the reader needs
 * to see.
 */
internal fun SenderServerSearchStatus.finished(): SenderServerSearchStatus =
    if (this is SenderServerSearchStatus.Searching) SenderServerSearchStatus.Finished(newMessages) else this

/**
 * The line shown under the sender's address, or `null` when there is nothing to say.
 */
internal fun SenderServerSearchStatus.describe(resources: Resources): String? = when (this) {
    SenderServerSearchStatus.NotStarted -> null
    is SenderServerSearchStatus.Searching -> if (newMessages == 0) {
        resources.getString(R.string.sender_server_search_running)
    } else {
        resources.getQuantityString(R.plurals.sender_server_search_running_found, newMessages, newMessages)
    }
    is SenderServerSearchStatus.Finished -> if (newMessages == 0) {
        resources.getString(R.string.sender_server_search_nothing_new)
    } else {
        resources.getQuantityString(R.plurals.sender_server_search_found, newMessages, newMessages)
    }
    SenderServerSearchStatus.Offline -> resources.getString(R.string.sender_server_search_offline)
    SenderServerSearchStatus.Failed -> resources.getString(R.string.sender_server_search_failed)
}
