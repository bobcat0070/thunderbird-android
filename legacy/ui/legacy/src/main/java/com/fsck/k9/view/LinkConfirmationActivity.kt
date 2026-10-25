package com.fsck.k9.view

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ContextThemeWrapper
import net.thunderbird.core.ui.theme.api.ThemeManager
import org.koin.android.ext.android.inject

/**
 * Shows the [LinkConfirmation] dialog for a link opened from somewhere that has no screen of its own to show it on,
 * such as a Compose screen asking through a bridge with only the application context. Invisible itself; it closes as
 * soon as the link has been opened or the dialog dismissed.
 */
class LinkConfirmationActivity : Activity() {
    private val linkConfirmation: LinkConfirmation by inject()
    private val themeManager: ThemeManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent.data
        // Recreated, e.g. after a rotation: the dialog went with the old instance, and asking again unbidden would
        // be stranger than letting it go.
        if (uri == null || savedInstanceState != null) {
            finish()
            return
        }

        val themedContext = ContextThemeWrapper(this, themeManager.appThemeResourceId)
        linkConfirmation.confirmAndOpen(themedContext, uri, onClosed = ::finish)
    }

    companion object {
        fun start(context: Context, uri: Uri) {
            val intent = Intent(context, LinkConfirmationActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
