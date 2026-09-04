package net.thunderbird.core.preference.widget

const val WIDGET_SETTINGS_DEFAULT_SHOW_PERSONAL = true
const val WIDGET_SETTINGS_DEFAULT_SHOW_NOTIFICATIONS = true
const val WIDGET_SETTINGS_DEFAULT_SHOW_NEWSLETTERS = true

/**
 * Which categories of mail the home screen widget lists.
 *
 * All on by default, so the widget keeps showing what it always showed until the user narrows it. The point
 * of narrowing it is that a widget is glanced at rather than read: someone who only wants to know whether a
 * person wrote to them is poorly served by a list that a newsletter can push off the bottom.
 *
 * @param showPersonal covers mail that was not identified as bulk, including mail the classifier could not
 *   place. Unknown mail belongs here rather than nowhere, because hiding it would lose real messages.
 */
data class WidgetSettings(
    val showPersonal: Boolean = WIDGET_SETTINGS_DEFAULT_SHOW_PERSONAL,
    val showNotifications: Boolean = WIDGET_SETTINGS_DEFAULT_SHOW_NOTIFICATIONS,
    val showNewsletters: Boolean = WIDGET_SETTINGS_DEFAULT_SHOW_NEWSLETTERS,
)
