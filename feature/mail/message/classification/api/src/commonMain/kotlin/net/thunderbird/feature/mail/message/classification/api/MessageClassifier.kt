package net.thunderbird.feature.mail.message.classification.api

/**
 * The facts a classifier is allowed to look at.
 *
 * Deliberately a plain value rather than a parsed message: classification is a pure decision over evidence, so
 * it can be tested against captured real-world headers without a mail store, a network or a parser.
 *
 * @param headers header values by lower-case name. A header may legitimately appear more than once.
 * @param fromAddress the bare sender address, without display name, lower-cased.
 * @param recipientCount how many addresses the message was visibly addressed to, across To and Cc.
 */
data class MessageEvidence(
    val headers: Map<String, List<String>>,
    val fromAddress: String? = null,
    val recipientCount: Int = 0,
) {
    /**
     * @return the first value of [name], or `null` when the header is absent or empty.
     */
    fun firstHeader(name: String): String? =
        headers[name.lowercase()]?.firstOrNull { it.isNotBlank() }

    fun hasHeader(name: String): Boolean = firstHeader(name) != null
}

/**
 * Decides what kind of mail a message is.
 */
fun interface MessageClassifier {
    fun classify(evidence: MessageEvidence): MessageClassification
}
