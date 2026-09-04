package com.fsck.k9.mailstore

/**
 * The header the receiving server writes to record what it checked.
 *
 * A message may carry several, one per hop. Only the ones added by the server that delivered to this mailbox
 * can be trusted, but a client cannot tell those apart from ones a sender fabricated further upstream, so the
 * result is treated as a hint that unlocks a brand logo and never as proof of anything on its own.
 */
private const val AUTHENTICATION_RESULTS = "Authentication-Results"

/**
 * The one DMARC verdict that means the domain's own published policy was applied and passed.
 *
 * Deliberately not `bestguesspass`, which is Microsoft's guess for domains that publish no DMARC record at
 * all. Treating a guess as a pass is exactly how a brand indicator becomes a phishing aid.
 */
private const val DMARC_PASS = "pass"

private val DMARC_RESULT = Regex("""\bdmarc=([a-z]+)""", RegexOption.IGNORE_CASE)

/**
 * Whether the receiving server reported that this message passed DMARC.
 *
 * DMARC passing is what ties the From domain to a sender authorised by that domain, which is the only reason
 * it is safe to show that domain's logo. Without it a brand indicator says nothing about who actually sent
 * the message.
 *
 * @param headerValues every `Authentication-Results` header on the message.
 */
fun hasDmarcPass(headerValues: List<String>): Boolean {
    return headerValues.any { value ->
        DMARC_RESULT.find(value)?.groupValues?.get(1)?.lowercase() == DMARC_PASS
    }
}

/**
 * The header name callers should retain and pass to [hasDmarcPass].
 */
fun authenticationResultsHeaderName(): String = AUTHENTICATION_RESULTS
