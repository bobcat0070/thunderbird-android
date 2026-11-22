package net.thunderbird.app.common.feature

import android.content.Context
import app.k9mail.feature.launcher.FeatureLauncherActivity
import app.k9mail.feature.launcher.FeatureLauncherTarget
import com.fsck.k9.activity.MessageHomeActivity
import com.fsck.k9.ui.messagelist.SavedSearchStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import net.thunderbird.core.preference.GeneralSettingsManager
import net.thunderbird.feature.navigation.drawer.api.NavigationDrawerExternalContract.DrawerShortcut
import net.thunderbird.feature.navigation.drawer.api.NavigationDrawerExternalContract.DrawerShortcuts

/**
 * The drawer's shortcuts: the searches the reader saved there, then Packages and Trips, each shown while its switch in
 * General settings is on, then Attachments and Expired mail, always.
 */
internal class DefaultDrawerShortcuts(
    private val generalSettingsManager: GeneralSettingsManager,
    private val savedSearchStore: SavedSearchStore,
) : DrawerShortcuts {
    // Starts from the settings as they are: the settings flow only emits again once something in it changes, so a
    // drawer made after that (a new activity, say) would otherwise show no shortcuts.
    override fun getShortcutsFlow(): Flow<List<DrawerShortcut>> = combine(
        generalSettingsManager.getConfigFlow().onStart { emit(generalSettingsManager.getConfig()) },
        savedSearchStore.savedSearches,
    ) { settings, savedSearches ->
        buildList {
            savedSearches.forEach { add(DrawerShortcut.SavedSearch(it.id, it.name)) }
            if (settings.interaction.isPackagesEnabled) add(DrawerShortcut.Packages)
            if (settings.interaction.isTripsEnabled) add(DrawerShortcut.Trips)
            add(DrawerShortcut.Attachments)
            add(DrawerShortcut.ExpiredMail)
        }
    }.distinctUntilChanged()

    override fun open(context: Context, shortcut: DrawerShortcut) {
        val target = when (shortcut) {
            DrawerShortcut.Packages -> FeatureLauncherTarget.Packages

            DrawerShortcut.Trips -> FeatureLauncherTarget.Trips

            DrawerShortcut.Attachments -> FeatureLauncherTarget.AllAttachments

            DrawerShortcut.ExpiredMail -> FeatureLauncherTarget.ExpiredMail

            is DrawerShortcut.SavedSearch -> {
                val savedSearch = savedSearchStore.savedSearches.value.firstOrNull { it.id == shortcut.id } ?: return
                context.startActivity(MessageHomeActivity.intentDisplaySavedSearch(context, savedSearch))
                return
            }
        }
        context.startActivity(FeatureLauncherActivity.getIntent(context, target))
    }
}
