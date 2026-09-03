package app.k9mail.feature.widget.message.list

import net.thunderbird.core.preference.widget.WidgetSettings
import net.thunderbird.feature.mail.message.classification.api.MessageClass

/**
 * Keeps only the categories the widget is set to show.
 *
 * Applied after loading rather than as a query condition, because a message classified before this app knew
 * about classification has no stored class at all. Deciding that in Kotlin keeps "no class recorded" and
 * "explicitly unknown" together on the personal side, which SQL would have made awkward and easy to get
 * wrong in the direction that loses mail.
 */
internal fun List<MessageListItem>.filterByCategory(settings: WidgetSettings): List<MessageListItem> {
    // Everything on is the default, and filtering nothing is cheaper than walking the list to decide that.
    if (settings.showPersonal && settings.showNotifications && settings.showNewsletters) return this

    return filter { item -> settings.shows(item.classification) }
}

private fun WidgetSettings.shows(messageClass: MessageClass): Boolean = when (messageClass) {
    MessageClass.NOTIFICATION -> showNotifications

    MessageClass.NEWSLETTER -> showNewsletters

    // Mail the classifier could not place counts as personal: the widget is a glance at what might need the
    // user, and burying a message we could not identify is the failure that actually costs something.
    MessageClass.HUMAN, MessageClass.UNKNOWN -> showPersonal
}
