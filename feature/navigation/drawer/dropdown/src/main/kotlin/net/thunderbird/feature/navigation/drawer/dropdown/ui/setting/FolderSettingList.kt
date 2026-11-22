package net.thunderbird.feature.navigation.drawer.dropdown.ui.setting

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import net.thunderbird.components.ui.bolt.atom.icon.Icons
import net.thunderbird.components.ui.bolt.common.window.WindowHeightSizeClass
import net.thunderbird.components.ui.bolt.common.window.WindowWidthSizeClass
import net.thunderbird.components.ui.bolt.common.window.calculateWindowSizeInfo
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import net.thunderbird.feature.navigation.drawer.api.NavigationDrawerExternalContract.DrawerShortcut
import net.thunderbird.feature.navigation.drawer.dropdown.R

@Composable
@Suppress("LongMethod")
internal fun FolderSettingList(
    onSyncAccountClick: () -> Unit,
    onSyncAllAccountsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    isUnifiedAccount: Boolean,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    shortcuts: ImmutableList<DrawerShortcut> = persistentListOf(),
    onShortcutClick: (DrawerShortcut) -> Unit = {},
) {
    val windowSizeInfo = calculateWindowSizeInfo()
    val isLandscape = windowSizeInfo.size.width > windowSizeInfo.size.height
    val isCompactHeight = windowSizeInfo.sizeClass.heightSizeClass == WindowHeightSizeClass.Compact
    val isSmallDisplay = windowSizeInfo.sizeClass.widthSizeClass == WindowWidthSizeClass.Small ||
        windowSizeInfo.sizeClass.heightSizeClass == WindowHeightSizeClass.Small
    val hideText = (isLandscape && isCompactHeight) || isSmallDisplay

    SettingList(
        modifier = if (hideText) {
            modifier
                .padding(vertical = BoltTheme.spacings.double)
                .fillMaxWidth()
                .height(BoltTheme.sizes.iconAvatar)
        } else {
            modifier
                .padding(vertical = BoltTheme.spacings.default)
                .fillMaxWidth()
        },
    ) {
        shortcuts.forEach { shortcut ->
            item(span = { if (hideText) GridItemSpan(1) else GridItemSpan(maxLineSpan) }) {
                SettingListItem(
                    label = shortcut.label(),
                    onClick = { onShortcutClick(shortcut) },
                    icon = shortcut.icon(),
                )
            }
        }
        if (isUnifiedAccount.not()) {
            item(span = { if (hideText) GridItemSpan(1) else GridItemSpan(maxLineSpan) }) {
                SettingListItem(
                    label = stringResource(id = R.string.navigation_drawer_dropdown_action_sync_account),
                    onClick = onSyncAccountClick,
                    icon = Icons.Outlined.Sync,
                    isLoading = isLoading,
                )
            }
        } else {
            item(span = { if (hideText) GridItemSpan(1) else GridItemSpan(maxLineSpan) }) {
                SettingListItem(
                    label = stringResource(id = R.string.navigation_drawer_dropdown_action_sync_all_accounts),
                    onClick = onSyncAllAccountsClick,
                    icon = Icons.Outlined.Sync,
                    isLoading = isLoading,
                )
            }
        }
        item(span = { if (hideText) GridItemSpan(1) else GridItemSpan(maxLineSpan) }) {
            SettingListItem(
                label = stringResource(id = R.string.navigation_drawer_dropdown_action_settings),
                onClick = onSettingsClick,
                icon = Icons.Outlined.Settings,
            )
        }
    }
}

@Composable
private fun DrawerShortcut.label(): String = when (this) {
    DrawerShortcut.Packages -> stringResource(R.string.navigation_drawer_dropdown_action_packages)
    DrawerShortcut.Trips -> stringResource(R.string.navigation_drawer_dropdown_action_trips)
    DrawerShortcut.Attachments -> stringResource(R.string.navigation_drawer_dropdown_action_attachments)
    DrawerShortcut.ExpiredMail -> stringResource(R.string.navigation_drawer_dropdown_action_expired_mail)
    is DrawerShortcut.SavedSearch -> name
}

private fun DrawerShortcut.icon() = when (this) {
    DrawerShortcut.Packages -> Icons.Outlined.LocalShipping
    DrawerShortcut.Trips -> Icons.Outlined.Flight
    DrawerShortcut.Attachments -> Icons.Outlined.Attachment
    DrawerShortcut.ExpiredMail -> Icons.Outlined.Archive
    is DrawerShortcut.SavedSearch -> Icons.Outlined.Search
}
