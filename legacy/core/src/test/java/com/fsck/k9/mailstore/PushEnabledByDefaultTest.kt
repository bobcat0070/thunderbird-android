package com.fsck.k9.mailstore

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fsck.k9.mail.FolderType
import net.thunderbird.core.common.mail.Protocols
import org.junit.Test

class PushEnabledByDefaultTest {

    @Test
    fun `inbox of a Graph account should opt in`() {
        // Graph cannot be checked promptly any other way, so without push the account only refreshes every 15 minutes.
        assertThat(isPushEnabledByDefault(Protocols.GRAPH, FolderType.INBOX)).isTrue()
    }

    @Test
    fun `other folders of a Graph account should not opt in`() {
        assertThat(isPushEnabledByDefault(Protocols.GRAPH, FolderType.ARCHIVE)).isFalse()
        assertThat(isPushEnabledByDefault(Protocols.GRAPH, FolderType.SENT)).isFalse()
        assertThat(isPushEnabledByDefault(Protocols.GRAPH, FolderType.REGULAR)).isFalse()
    }

    @Test
    fun `inbox of an IMAP account should keep the previous default`() {
        // IMAP has IDLE, and enabling push would be a behaviour change plus a permanent notification.
        assertThat(isPushEnabledByDefault(Protocols.IMAP, FolderType.INBOX)).isFalse()
    }

    @Test
    fun `inbox of a POP3 account should keep the previous default`() {
        assertThat(isPushEnabledByDefault(Protocols.POP3, FolderType.INBOX)).isFalse()
    }
}
