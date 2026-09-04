package com.fsck.k9.notification

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import net.thunderbird.core.preference.notification.NotificationPreference
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import org.junit.Test

class NotificationCategoryFilterTest {

    @Test
    fun `everything enabled should notify for every category`() {
        val preference = preference()

        for (messageClass in MessageClass.entries) {
            assertThat(preference.notifiesFor(messageClass)).isTrue()
        }
    }

    @Test
    fun `everything enabled should be the default`() {
        // Notifications have to keep behaving exactly as they did until the user narrows them; a setting that
        // silences mail by arriving is one nobody asked for.
        for (messageClass in MessageClass.entries) {
            assertThat(NotificationPreference().notifiesFor(messageClass)).isTrue()
        }
    }

    @Test
    fun `personal only should stay quiet for bulk mail`() {
        val preference = preference(personal = true, notifications = false, newsletters = false)

        assertThat(preference.notifiesFor(MessageClass.HUMAN)).isTrue()
        assertThat(preference.notifiesFor(MessageClass.NOTIFICATION)).isFalse()
        assertThat(preference.notifiesFor(MessageClass.NEWSLETTER)).isFalse()
    }

    @Test
    fun `unclassified mail should follow the personal setting`() {
        // Most mail carries no positive evidence of being written by a person, and mail stored before this
        // app classified anything has no class at all. Silencing that would quietly drop real messages.
        assertThat(preference(personal = true).notifiesFor(MessageClass.UNKNOWN)).isTrue()
        assertThat(preference(personal = false).notifiesFor(MessageClass.UNKNOWN)).isFalse()
    }

    @Test
    fun `notifications only should stay quiet for people and newsletters`() {
        val preference = preference(personal = false, notifications = true, newsletters = false)

        assertThat(preference.notifiesFor(MessageClass.NOTIFICATION)).isTrue()
        assertThat(preference.notifiesFor(MessageClass.HUMAN)).isFalse()
        assertThat(preference.notifiesFor(MessageClass.UNKNOWN)).isFalse()
        assertThat(preference.notifiesFor(MessageClass.NEWSLETTER)).isFalse()
    }

    @Test
    fun `newsletters only should stay quiet for everything else`() {
        val preference = preference(personal = false, notifications = false, newsletters = true)

        assertThat(preference.notifiesFor(MessageClass.NEWSLETTER)).isTrue()
        assertThat(preference.notifiesFor(MessageClass.NOTIFICATION)).isFalse()
        assertThat(preference.notifiesFor(MessageClass.HUMAN)).isFalse()
    }

    @Test
    fun `everything disabled should notify for nothing`() {
        val preference = preference(personal = false, notifications = false, newsletters = false)

        for (messageClass in MessageClass.entries) {
            assertThat(preference.notifiesFor(messageClass)).isFalse()
        }
    }

    private fun preference(
        personal: Boolean = true,
        notifications: Boolean = true,
        newsletters: Boolean = true,
    ) = NotificationPreference(
        isNotifyPersonal = personal,
        isNotifyNotifications = notifications,
        isNotifyNewsletters = newsletters,
    )
}
