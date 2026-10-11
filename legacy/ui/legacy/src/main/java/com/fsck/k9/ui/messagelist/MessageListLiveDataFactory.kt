package com.fsck.k9.ui.messagelist

import app.k9mail.legacy.mailstore.MessageListRepository
import com.fsck.k9.controller.MailActionHold
import kotlinx.coroutines.CoroutineScope
import net.thunderbird.core.android.account.LegacyAccountManager

class MessageListLiveDataFactory(
    private val messageListLoader: MessageListLoader,
    private val accountManager: LegacyAccountManager,
    private val messageListRepository: MessageListRepository,
    private val mailActionHold: MailActionHold,
) {
    /**
     * @param showNewestFirst whether the newest messages may be shown before the whole list has loaded.
     */
    fun create(
        coroutineScope: CoroutineScope,
        config: MessageListConfig,
        showNewestFirst: Boolean,
    ): MessageListLiveData {
        return MessageListLiveData(
            messageListLoader,
            accountManager,
            messageListRepository,
            mailActionHold,
            coroutineScope,
            config,
            showNewestFirst,
        )
    }
}
