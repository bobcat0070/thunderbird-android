package com.fsck.k9.mailstore

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test

/**
 * The shared message query names its columns in one string and reads them back by hard-coded position, so the
 * two have to agree. Nothing enforces that: adding a column in the middle, or appending one without moving the
 * indices of what other queries append after it, silently makes every message read the wrong field - and reads
 * that far off usually look like data, not like a crash.
 */
class GetMessagesColumnIndexTest {
    private val columns = LocalStore.GET_MESSAGES_COLS.split(",").map { it.trim().substringAfter('.') }

    @Test
    fun `every index should name the column it is used to read`() {
        assertThat(columns[LocalStore.MSG_INDEX_SUBJECT]).isEqualTo("subject")
        assertThat(columns[LocalStore.MSG_INDEX_SENDER_LIST]).isEqualTo("sender_list")
        assertThat(columns[LocalStore.MSG_INDEX_DATE]).isEqualTo("date")
        assertThat(columns[LocalStore.MSG_INDEX_UID]).isEqualTo("uid")
        assertThat(columns[LocalStore.MSG_INDEX_FLAGS]).isEqualTo("flags")
        assertThat(columns[LocalStore.MSG_INDEX_ID]).isEqualTo("id")
        assertThat(columns[LocalStore.MSG_INDEX_TO]).isEqualTo("to_list")
        assertThat(columns[LocalStore.MSG_INDEX_CC]).isEqualTo("cc_list")
        assertThat(columns[LocalStore.MSG_INDEX_BCC]).isEqualTo("bcc_list")
        assertThat(columns[LocalStore.MSG_INDEX_REPLY_TO]).isEqualTo("reply_to_list")
        assertThat(columns[LocalStore.MSG_INDEX_ATTACHMENT_COUNT]).isEqualTo("attachment_count")
        assertThat(columns[LocalStore.MSG_INDEX_INTERNAL_DATE]).isEqualTo("internal_date")
        assertThat(columns[LocalStore.MSG_INDEX_MESSAGE_ID_HEADER]).isEqualTo("message_id")
        assertThat(columns[LocalStore.MSG_INDEX_FOLDER_ID]).isEqualTo("folder_id")
        assertThat(columns[LocalStore.MSG_INDEX_PREVIEW]).isEqualTo("preview")
        assertThat(columns[LocalStore.MSG_INDEX_THREAD_ID]).isEqualTo("id")
        assertThat(columns[LocalStore.MSG_INDEX_THREAD_ROOT_ID]).isEqualTo("root")
        assertThat(columns[LocalStore.MSG_INDEX_FLAG_DELETED]).isEqualTo("deleted")
        assertThat(columns[LocalStore.MSG_INDEX_FLAG_READ]).isEqualTo("read")
        assertThat(columns[LocalStore.MSG_INDEX_FLAG_FLAGGED]).isEqualTo("flagged")
        assertThat(columns[LocalStore.MSG_INDEX_FLAG_ANSWERED]).isEqualTo("answered")
        assertThat(columns[LocalStore.MSG_INDEX_FLAG_FORWARDED]).isEqualTo("forwarded")
        assertThat(columns[LocalStore.MSG_INDEX_MESSAGE_PART_ID]).isEqualTo("message_part_id")
        assertThat(columns[LocalStore.MSG_INDEX_MIME_TYPE]).isEqualTo("mime_type")
        assertThat(columns[LocalStore.MSG_INDEX_PREVIEW_TYPE]).isEqualTo("preview_type")
        assertThat(columns[LocalStore.MSG_INDEX_HEADER_DATA]).isEqualTo("header")
        assertThat(columns[LocalStore.MSG_INDEX_CLASSIFICATION]).isEqualTo("classification")
        assertThat(columns[LocalStore.MSG_INDEX_SENDER_AUTHENTICATED]).isEqualTo("sender_authenticated")
    }

    @Test
    fun `appended columns should follow the shared list`() {
        // Some queries add their own columns after this list. Their indices are stated separately, so growing
        // the list without moving them would have them read the last shared columns instead.
        assertThat(LocalStore.MSG_INDEX_NOTIFICATION_ID).isEqualTo(columns.size)
        assertThat(LocalStore.MSG_INDEX_NOTIFICATION_TIMESTAMP).isEqualTo(columns.size + 1)
    }
}
