package com.fsck.k9.notification

import net.thunderbird.core.preference.notification.NotificationPreference
import net.thunderbird.feature.mail.message.classification.api.MessageClass

/**
 * Whether this category of mail is worth interrupting someone for.
 *
 * The rest of the app already tells a shipping notice from a newsletter from a message a person wrote.
 * Buzzing identically for all three throws that away, and this is the one place where using it saves the user
 * something they notice: a phone that stays quiet unless it matters.
 *
 * Everything on by default, so notifications keep behaving exactly as they did until the user narrows them.
 */
internal fun NotificationPreference.notifiesFor(messageClass: MessageClass): Boolean = when (messageClass) {
    MessageClass.NOTIFICATION -> isNotifyNotifications

    MessageClass.NEWSLETTER -> isNotifyNewsletters

    // Mail the classifier could not place counts as personal. Silencing a message nobody could identify is
    // how this setting would lose someone something that mattered, so it stays on the side that still rings.
    MessageClass.HUMAN, MessageClass.UNKNOWN -> isNotifyPersonal
}
