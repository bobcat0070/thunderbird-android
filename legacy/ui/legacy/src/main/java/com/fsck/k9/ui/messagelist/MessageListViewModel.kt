package com.fsck.k9.ui.messagelist

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.k9mail.legacy.message.controller.MessageReference
import java.util.LinkedList
import net.thunderbird.components.core.logging.Logger

private const val TAG = "LegacyMessageListViewModel"

class MessageListViewModel(
    private val messageListLiveDataFactory: MessageListLiveDataFactory,
    private val logger: Logger,
) : ViewModel() {
    private var currentMessageListLiveData: MessageListLiveData? = null
    private val messageListLiveData = MediatorLiveData<MessageListInfo>()

    val messageSortOverrides = LinkedList<Pair<MessageReference, MessageSortOverride>>()

    fun getMessageListLiveData(): LiveData<MessageListInfo> {
        return messageListLiveData
    }

    fun loadMessageList(config: MessageListConfig, forceUpdate: Boolean = false) {
        logger.debug(TAG) { "loadMessageList() called with: config = $config, forceUpdate = $forceUpdate" }
        if (!forceUpdate && currentMessageListLiveData?.config == config) return

        // The list on screen stays until the new one has loaded. When it is the same list - a message was opened or
        // closed - the newest messages must not stand in for it: they cut the list short under the reader, and it
        // lost its place.
        val isSameList = currentMessageListLiveData?.config?.isSameListAs(config) == true
        removeCurrentMessageListLiveData()

        val liveData = messageListLiveDataFactory.create(viewModelScope, config, showNewestFirst = !isSameList)
        currentMessageListLiveData = liveData

        messageListLiveData.addSource(liveData) { items ->
            messageListLiveData.value = items
        }
    }

    private fun removeCurrentMessageListLiveData() {
        currentMessageListLiveData?.let {
            currentMessageListLiveData = null
            messageListLiveData.removeSource(it)
        }
    }
}
