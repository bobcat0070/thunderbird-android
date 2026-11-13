package net.thunderbird.feature.packages

/**
 * The parcels the reader marked delivered or removed, so that settings export can carry them to another device.
 *
 * A parcel is named by its tracking number, which means the same on any device, and each mark keeps when it was made
 * so it expires on the new device when it would have here.
 */
interface ParcelMarkSettings {
    /**
     * @return each tracking number marked delivered, and each one removed from the list, with when it was marked in
     *   milliseconds since the epoch. Marks that have expired are left out.
     */
    fun exportMarks(): ParcelMarkSnapshot

    /**
     * Adds [marks] to the marks already here. A number marked here as well keeps the later of the two times.
     */
    fun importMarks(marks: ParcelMarkSnapshot)
}

/**
 * @property markedDelivered tracking numbers the reader said have arrived, each with when.
 * @property removed tracking numbers the reader took off the list, each with when.
 */
data class ParcelMarkSnapshot(
    val markedDelivered: Map<String, Long>,
    val removed: Map<String, Long>,
)
