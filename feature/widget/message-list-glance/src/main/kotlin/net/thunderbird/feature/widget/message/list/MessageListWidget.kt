package net.thunderbird.feature.widget.message.list

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.PendingIntentCompat
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import com.fsck.k9.CoreResourceProvider
import com.fsck.k9.activity.MessageHomeActivity.Companion.intentDisplaySearch
import com.fsck.k9.contacts.ContactPictureLoader
import kotlin.random.Random.Default.nextInt
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.thunderbird.core.android.account.SortType
import net.thunderbird.core.preference.GeneralSettingsManager
import net.thunderbird.feature.search.legacy.SearchAccount.Companion.createUnifiedFoldersSearch
import net.thunderbird.feature.widget.message.list.ui.MessageListWidgetContent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal class MessageListWidget : GlanceAppWidget(), KoinComponent {

    private val messageListLoader: MessageListLoader by inject()
    private val coreResourceProvider: CoreResourceProvider by inject()
    private val generalSettingsManager: GeneralSettingsManager by inject()
    private val contactPictureLoader: ContactPictureLoader by inject()

    companion object {
        private var lastMailList = emptyList<MessageListItem>()

        /**
         * How many messages the widget lists. The whole list reaches the home screen in one update, and the launcher
         * refuses one much over half a megabyte - and then stops taking updates for any widget at all. A row takes
         * about 4 KB, so a hundred of them with pictures made an update of 548 KB, which was refused.
         */
        private const val MESSAGE_COUNT = 40

        /**
         * How many rows get a picture: the rows a person sees without scrolling. Each picture adds 16 KB to the
         * update, see [MESSAGE_COUNT].
         */
        private const val PICTURE_ROWS = 8

        /**
         * The size pictures are scaled to before they are sent, well above the size they are drawn at but a
         * fraction of the bytes the full-size picture would take.
         */
        private const val PICTURE_SIZE_PX = 64
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            var mails by remember { mutableStateOf(lastMailList) }

            LaunchedEffect(Unit) {
                CoroutineScope(Dispatchers.IO).launch {
                    val unifiedInboxSearch = createUnifiedFoldersSearch(
                        title = coreResourceProvider.searchUnifiedFoldersTitle(),
                        detail = coreResourceProvider.searchUnifiedFoldersDetail(),
                    ).relatedSearch
                    val messageListConfig = MessageListConfig(
                        search = unifiedInboxSearch,
                        showingThreadedList = generalSettingsManager.getConfig()
                            .display.inboxSettings.isThreadedViewEnabled,
                        sortType = SortType.SORT_DATE,
                        sortAscending = false,
                        sortDateAscending = false,
                    )
                    val list = messageListLoader.getMessageList(messageListConfig)
                    mails = withPictures(list.subList(0, list.size.coerceAtMost(MESSAGE_COUNT)))
                    lastMailList = mails
                }
            }

            MessageListWidgetContent(
                mails = mails.toImmutableList(),
                onOpenApp = { openApp(context) },
            )
        }
    }

    /**
     * Adds the same pictures the message list shows to the first rows - only ones already cached, since a widget
     * update cannot wait on the network - behind the same "show contact pictures" setting.
     */
    private fun withPictures(items: List<MessageListItem>): List<MessageListItem> {
        val showPictures = generalSettingsManager.getConfig()
            .display
            .visualSettings
            .messageListSettings
            .isShowContactPicture
        if (!showPictures) return items

        return items.mapIndexed { index, item ->
            val address = item.displayAddress
            if (index >= PICTURE_ROWS || address == null) return@mapIndexed item

            val picture = contactPictureLoader.getContactPicture(address, item.isSenderAuthenticated, cachedOnly = true)
                ?.let { Bitmap.createScaledBitmap(it, PICTURE_SIZE_PX, PICTURE_SIZE_PX, true) }

            item.copy(picture = picture)
        }
    }

    private fun openApp(context: Context) {
        val unifiedFoldersSearch = createUnifiedFoldersSearch(
            title = coreResourceProvider.searchUnifiedFoldersTitle(),
            detail = coreResourceProvider.searchUnifiedFoldersDetail(),
        )
        val intent = intentDisplaySearch(
            context = context,
            search = unifiedFoldersSearch.relatedSearch,
            noThreading = true,
            newTask = true,
            clearTop = true,
        ).apply {
            action = nextInt().toString()
        }
        PendingIntentCompat.getActivity(
            context,
            nextInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT,
            false,
        )!!.send()
    }
}
