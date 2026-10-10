package com.fsck.k9.ui.messagelist

import androidx.lifecycle.LiveData
import app.k9mail.legacy.mailstore.MessageListChangedListener
import app.k9mail.legacy.mailstore.MessageListRepository
import app.k9mail.legacy.message.controller.MessageReference
import com.fsck.k9.controller.MailActionHold
import com.fsck.k9.search.getLegacyAccountUuids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.account.AccountIdFactory

class MessageListLiveData(
    private val messageListLoader: MessageListLoader,
    private val accountManager: LegacyAccountManager,
    private val messageListRepository: MessageListRepository,
    private val mailActionHold: MailActionHold,
    private val coroutineScope: CoroutineScope,
    val config: MessageListConfig,
) : LiveData<MessageListInfo>() {

    /**
     * One load of the list at a time. A sync reports a change for every message it saves, far faster than a large
     * list loads; starting a load for each piled up copies of the whole list until the app ran out of memory. The
     * pause between loads leaves the database to the sync in between, which otherwise waited on back-to-back reads.
     */
    private val loader = ConflatingLoader(
        scope = coroutineScope + Dispatchers.Main,
        loadContext = Dispatchers.IO,
        load = { messageListLoader.getMessageList(config) },
        onLoaded = { messageList -> value = messageList },
        pauseAfterLoadMillis = PAUSE_BETWEEN_LOADS_MILLIS,
    )

    private val messageListChangedListener = MessageListChangedListener {
        loadMessageListAsync()
    }

    private fun loadMessageListAsync() {
        loader.requestLoad()
    }

    /**
     * Reloads the list when mail is held for deleting or moving, or let go by "Undo", which is when it leaves the
     * list or comes back to it.
     */
    private var heldActionsJob: Job? = null

    override fun onActive() {
        super.onActive()

        registerMessageListChangedListenerAsync()
        heldActionsJob = coroutineScope.launch(Dispatchers.Main) {
            mailActionHold.heldActions.drop(1).collect { loadMessageListAsync() }
        }

        if (value == null) {
            showNewestMessagesThenAll()
        } else {
            loadMessageListAsync()
        }
    }

    /**
     * Shows the newest messages before the whole list has loaded.
     *
     * A large list - a unified inbox holds thousands of messages - takes a second or more to read, and until then the
     * screen is blank. The newest [FIRST_LOAD_LIMIT] take a fraction of that and fill the screen; the rest of the list
     * follows below them. The short list is shown only if the whole one has not arrived first, and only if it holds
     * the message being read.
     */
    private fun showNewestMessagesThenAll() {
        coroutineScope.launch(Dispatchers.Main) {
            val newestMessages = withContext(Dispatchers.IO) {
                messageListLoader.getMessageList(config, limit = FIRST_LOAD_LIMIT)
            }
            if (value == null && newestMessages.canStandInForWholeList(config.activeMessage)) value = newestMessages

            loadMessageListAsync()
        }
    }

    override fun onInactive() {
        super.onInactive()
        messageListRepository.removeListener(messageListChangedListener)
        heldActionsJob?.cancel()
        heldActionsJob = null
    }

    private fun registerMessageListChangedListenerAsync() {
        coroutineScope.launch(Dispatchers.IO) {
            val accountUuids = config.search.getLegacyAccountUuids(accountManager)

            for (accountUuid in accountUuids) {
                messageListRepository.addListener(AccountIdFactory.of(accountUuid), messageListChangedListener)
            }
        }
    }
}

/**
 * Whether these, the newest messages of a list, can be shown until the whole list has loaded.
 *
 * The message being read has to be in the list shown with it: the reader closes as soon as a list arrives without it.
 * An older message falls below the newest few, and a short list without it closed the reader the moment it opened.
 */
internal fun MessageListInfo.canStandInForWholeList(activeMessage: MessageReference?): Boolean {
    return activeMessage == null || messageListItems.any { it.messageReference == activeMessage }
}

private const val PAUSE_BETWEEN_LOADS_MILLIS = 500L

/**
 * How many messages are shown before the rest of a list has loaded: a few screens' worth.
 */
private const val FIRST_LOAD_LIMIT = 100
