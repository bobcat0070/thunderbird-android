package net.thunderbird.feature.packages

/**
 * The shipping mail of parcels that arrived a while ago: mail whose use is over.
 */
fun interface DeliveredParcelMail {
    /**
     * @param deliveredBefore the latest a parcel's last mail may be dated, in milliseconds since the epoch, for its
     *   mail to count.
     * @return the keys, as [ShippingMail.key], of every mail about a parcel that was delivered - by its mail's word or
     *   the reader's - and has not been mentioned since [deliveredBefore]. A mail about several parcels counts only
     *   when all of them are.
     */
    suspend fun deliveredMailKeys(deliveredBefore: Long): Set<String>
}
