package net.thunderbird.feature.navigation.drawer.api

import androidx.appcompat.app.AppCompatActivity

interface NavigationDrawer {
    val parent: AppCompatActivity
    val isOpen: Boolean

    fun selectAccount(accountUuid: String)

    fun selectFolder(accountUuid: String, folderId: Long)

    fun selectUnifiedInbox()

    /**
     * Highlights the unified folder a search shows - the unified Sent folder, say.
     *
     * @param searchId the id of the search being displayed. A search that is not a unified folder leaves the
     *   selection as it is.
     */
    fun selectUnifiedFolder(searchId: String)

    fun deselect()

    fun open()

    fun close()

    fun lock()

    fun unlock()
}
