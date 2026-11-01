package net.thunderbird.app.common.feature

import android.content.Context
import app.k9mail.feature.launcher.FeatureLauncherActivity
import app.k9mail.feature.launcher.FeatureLauncherTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import net.thunderbird.core.preference.GeneralSettingsManager
import net.thunderbird.feature.navigation.drawer.api.NavigationDrawerExternalContract.DrawerShortcut
import net.thunderbird.feature.navigation.drawer.api.NavigationDrawerExternalContract.DrawerShortcuts

/**
 * The drawer's Packages and Trips entries, each shown while its switch in General settings is on.
 */
internal class DefaultDrawerShortcuts(
    private val generalSettingsManager: GeneralSettingsManager,
) : DrawerShortcuts {
    // Starts from the settings as they are: the settings flow only emits again once something in it changes, so a
    // drawer made after that (a new activity, say) would otherwise show no shortcuts.
    override fun getShortcutsFlow(): Flow<List<DrawerShortcut>> = generalSettingsManager.getConfigFlow()
        .onStart { emit(generalSettingsManager.getConfig()) }
        .map { settings ->
            buildList {
                if (settings.interaction.isPackagesEnabled) add(DrawerShortcut.PACKAGES)
                if (settings.interaction.isTripsEnabled) add(DrawerShortcut.TRIPS)
            }
        }
        .distinctUntilChanged()

    override fun open(context: Context, shortcut: DrawerShortcut) {
        val target = when (shortcut) {
            DrawerShortcut.PACKAGES -> FeatureLauncherTarget.Packages
            DrawerShortcut.TRIPS -> FeatureLauncherTarget.Trips
        }
        context.startActivity(FeatureLauncherActivity.getIntent(context, target))
    }
}
