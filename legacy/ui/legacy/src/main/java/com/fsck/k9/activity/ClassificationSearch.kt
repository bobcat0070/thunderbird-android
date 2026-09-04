package com.fsck.k9.activity

import net.thunderbird.feature.mail.message.classification.api.MessageClass
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import net.thunderbird.feature.search.legacy.api.MessageSearchField
import net.thunderbird.feature.search.legacy.api.SearchAttribute

/**
 * @return a search for the messages of [messageClass] within whatever this search already covers.
 *
 * Carries the whole condition tree rather than the folder ids it happens to name. A search does not have to
 * name folders at all: the unified inbox is defined by an `integrate` condition and names none, so copying
 * folder ids alone widened the category list to every folder of every account - Sent, Junk, Archive and
 * Trash included. That also made a deleted message look undeletable, because deleting moves it to the trash
 * where it still matched, and a message on its way to the trash is marked read, so the row came back looking
 * as though the swipe had only marked it read.
 */
internal fun LocalMessageSearch.narrowedToClassification(messageClass: MessageClass): LocalMessageSearch {
    val source = this

    return LocalMessageSearch().apply {
        id = source.id
        source.accountUuids.forEach { accountUuid -> addAccountUuid(accountUuid) }
        and(source.conditions)
        and(MessageSearchField.CLASSIFICATION, messageClass.name, SearchAttribute.EQUALS)
    }
}
