package net.thunderbird.feature.trips

/**
 * The reader's changes to the travel sender list, so that settings export can carry them to another device.
 */
interface TravelSenderSettings {
    fun exportSenders(): TravelSenderSnapshot

    /**
     * Adds [senders] to the changes already made here: senders added or removed on either device stay that way, and
     * the marked mails of both are kept.
     */
    fun importSenders(senders: TravelSenderSnapshot)
}

/**
 * @property added domains the reader added to the list.
 * @property removed built-in domains the reader took off the list.
 * @property markedMessageIds Message-IDs of single mails the reader marked as trip mail.
 */
data class TravelSenderSnapshot(
    val added: Set<String>,
    val removed: Set<String>,
    val markedMessageIds: List<String>,
)
