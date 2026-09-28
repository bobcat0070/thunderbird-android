package com.fsck.k9.ui.messagelist

import androidx.lifecycle.LiveData
import app.k9mail.legacy.mailstore.MessageListChangedListener
import app.k9mail.legacy.mailstore.MessageListRepository
import com.fsck.k9.search.getLegacyAccountUuids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import net.thunderbird.core.android.account.LegacyAccountManager

class MessageListLiveData(
    private val messageListLoader: MessageListLoader,
    private val accountManager: LegacyAccountManager,
    private val messageListRepository: MessageListRepository,
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

    override fun onActive() {
        super.onActive()

        registerMessageListChangedListenerAsync()

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
     * follows below them. The short list is shown only if the whole one has not arrived first.
     */
    private fun showNewestMessagesThenAll() {
        coroutineScope.launch(Dispatchers.Main) {
            val newestMessages = withContext(Dispatchers.IO) {
                messageListLoader.getMessageList(config, limit = FIRST_LOAD_LIMIT)
            }
            if (value == null) value = newestMessages

            loadMessageListAsync()
        }
    }

    override fun onInactive() {
        super.onInactive()
        messageListRepository.removeListener(messageListChangedListener)
    }

    private fun registerMessageListChangedListenerAsync() {
        coroutineScope.launch(Dispatchers.IO) {
            val accountUuids = config.search.getLegacyAccountUuids(accountManager)

            for (accountUuid in accountUuids) {
                messageListRepository.addListener(accountUuid, messageListChangedListener)
            }
        }
    }
}

private const val PAUSE_BETWEEN_LOADS_MILLIS = 500L

/**
 * How many messages are shown before the rest of a list has loaded: a few screens' worth.
 */
private const val FIRST_LOAD_LIMIT = 100
