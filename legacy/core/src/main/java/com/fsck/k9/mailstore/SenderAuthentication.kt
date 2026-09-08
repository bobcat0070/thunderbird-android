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
 * The one result that counts as a pass, for DMARC and for every other mechanism reported in the header.
 *
 * Deliberately not `bestguesspass`, which is Microsoft's guess for domains that publish no DMARC record at
 * all. Treating a guess as a pass is exactly how a brand indicator becomes a phishing aid.
 */
private const val PASS = "pass"

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
        DMARC_RESULT.find(value)?.groupValues?.get(1)?.lowercase() == PASS
    }
}

/**
 * The header name callers should retain and pass to [hasDmarcPass].
 */
fun authenticationResultsHeaderName(): String = AUTHENTICATION_RESULTS

/**
 * The mechanisms a receiving server reports on, in the order they are shown to the reader.
 *
 * SPF and DKIM are the two ways a domain can authorise a message; DMARC is the domain's own verdict on
 * whether either of them lined up with the address the reader sees. Ordered weakest claim first, so the line
 * reads as a chain ending in the verdict that actually matters.
 */
enum class AuthenticationMethod(val label: String) {
    DKIM("DKIM"),
    SPF("SPF"),
    DMARC("DMARC"),
}

/**
 * What the receiving server reported for one mechanism.
 *
 * @param passed whether the mechanism both passed and lined up with the From domain. A mechanism the server
 *   did not report on is not passing: absence of a check is not evidence that a check succeeded, and a reader
 *   deciding whether to trust a message is better served by "nobody said this passed" than by silence.
 */
data class AuthenticationOutcome(
    val method: AuthenticationMethod,
    val passed: Boolean,
)

/**
 * Reads what the receiving server said it checked.
 *
 * A pass on its own is worth very little. SPF checks the envelope sender and DKIM checks whichever domain
 * signed, and either can pass for a domain that has nothing to do with the From address the reader is looking
 * at - which is exactly how a lookalike sender collects a row of green ticks. So a mechanism only counts here
 * when its own domain also lines up with the From domain, which is the same comparison DMARC makes.
 *
 * @param headerValues every `Authentication-Results` header on the message.
 * @param fromDomain the domain of the address shown as the sender.
 * @return one outcome per mechanism, or nothing at all when no server reported anything - which is not the
 *   same as everything failing and should not be shown as though it were.
 */
fun authenticationOutcomes(headerValues: List<String>, fromDomain: String?): List<AuthenticationOutcome> {
    val specs = headerValues.flatMap { value -> methodSpecs(value) }
    if (specs.isEmpty()) return emptyList()

    val domain = fromDomain?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    return AuthenticationMethod.entries.map { method ->
        AuthenticationOutcome(method, specs.any { spec -> spec.passes(method, domain) })
    }
}

/**
 * One `method=result` clause of the header, with the properties that say what it applied to.
 */
private class MethodSpec(
    val method: String,
    val result: String,
    val properties: Map<String, String>,
)

private val METHOD_RESULT = Regex("""^\s*([a-z][a-z0-9-]*)\s*=\s*([a-z]+)""", RegexOption.IGNORE_CASE)

private val PROPERTY = Regex("""\b([a-z]+\.[a-z-]+)\s*=\s*([^\s;]+)""", RegexOption.IGNORE_CASE)

private fun methodSpecs(headerValue: String): List<MethodSpec> =
    stripComments(headerValue).split(';').mapNotNull { chunk -> chunk.toMethodSpec() }

private fun String.toMethodSpec(): MethodSpec? {
    // The first chunk is the name of the server that did the checking and carries no "=", so it drops out here.
    val match = METHOD_RESULT.find(this) ?: return null

    val properties = PROPERTY.findAll(this).associate { property ->
        property.groupValues[1].lowercase() to property.groupValues[2].trim('"').lowercase()
    }

    return MethodSpec(match.groupValues[1].lowercase(), match.groupValues[2].lowercase(), properties)
}

/**
 * Removes the parenthesised comments servers write into the header.
 *
 * They are free text and routinely contain both semicolons and things shaped like results - Google's SPF
 * comment names the sending domain, and its DMARC comment carries `p=REJECT` - either of which would be read
 * as a clause of its own by anything splitting the header naively.
 */
private fun stripComments(value: String): String {
    val stripped = StringBuilder(value.length)
    var depth = 0
    var quoted = false

    for (character in value) {
        when {
            quoted -> {
                if (character == '"') quoted = false
                if (depth == 0) stripped.append(character)
            }

            character == '"' -> {
                quoted = true
                if (depth == 0) stripped.append(character)
            }

            character == '(' -> depth++
            character == ')' -> if (depth > 0) depth--
            depth == 0 -> stripped.append(character)
        }
    }

    return stripped.toString()
}

private fun MethodSpec.passes(method: AuthenticationMethod, fromDomain: String?): Boolean {
    if (this.method != method.name.lowercase() || result != PASS) return false

    val identity = authenticatedIdentity(method)?.domainPart()

    return when {
        // DMARC is itself the alignment check, so a server reporting a pass has already made this comparison
        // and a clause that names no From address of its own is taken at its word.
        identity == null -> method == AuthenticationMethod.DMARC
        fromDomain == null -> false
        else -> isAligned(fromDomain, identity)
    }
}

/**
 * What this clause actually authenticated, which is not necessarily the address the reader is shown.
 */
private fun MethodSpec.authenticatedIdentity(method: AuthenticationMethod): String? = when (method) {
    // The domain that signed. A message can carry several signatures - a forwarding list adds its own beside
    // the original - so every clause is asked and one aligned pass is enough.
    AuthenticationMethod.DKIM -> properties["header.d"] ?: properties["header.i"]

    // The envelope sender, which for most bulk mail is a bounce address at a subdomain of the sender.
    AuthenticationMethod.SPF -> properties["smtp.mailfrom"] ?: properties["smtp.helo"]

    // Which From address the server says its verdict was about, when it says.
    AuthenticationMethod.DMARC -> properties["header.from"]
}

/**
 * Whether an authenticated domain counts as the From domain.
 *
 * DMARC's relaxed mode compares organizational domains, which cannot be worked out exactly without the public
 * suffix list. Equality or a parent/child relationship covers what senders actually do - `mail.example.com`
 * signing for `example.com` - and erring towards "not aligned" is the safe direction: the cost is a strike
 * through a check that did line up, not a tick on one that did not.
 */
private fun isAligned(fromDomain: String, authenticatedDomain: String): Boolean {
    if (authenticatedDomain.isEmpty()) return false

    return fromDomain == authenticatedDomain ||
        fromDomain.endsWith(".$authenticatedDomain") ||
        authenticatedDomain.endsWith(".$fromDomain")
}

/**
 * Properties are sometimes a bare domain and sometimes a whole address, and either is acceptable in the
 * header, so both are reduced to the domain before comparing.
 */
private fun String.domainPart(): String = substringAfterLast('@').trim('<', '>', '"')
