package net.thunderbird.feature.plusaddress.internal

import okhttp3.HttpUrl

/**
 * Domains whose mailboxes ignore dots in the user name, so `first.last@` and `firstlast@` are one mailbox.
 */
private val DOT_INSENSITIVE_DOMAINS = setOf("gmail.com", "googlemail.com")

/**
 * The shortest tag or domain name compared by containment rather than in full: shorter ones, like `tv` or `go`,
 * turn up inside too many unrelated names.
 */
private const val MIN_CONTAINED_NAME_LENGTH = 4

/**
 * One of the reader's plus addresses.
 *
 * @property key the address in the form two spellings of one mailbox share, which is what it is looked up by.
 * @property address the address as it was written, in lower case, which is what the reader sees.
 * @property tag what follows the `+`.
 */
internal data class PlusAddress(val key: String, val address: String, val tag: String)

/**
 * The reader's own addresses, compared the way their mail servers compare them.
 */
internal class ReaderAddresses(addresses: Collection<String>) {
    private val canonical = addresses.mapNotNullTo(mutableSetOf(), ::canonicalAddress)

    fun contains(address: String): Boolean = canonicalAddress(address)?.let { it in canonical } == true

    /**
     * @return [recipient] as one of the reader's plus addresses, or `null` when it is no plus address, or a plus
     *   variant of somebody else's address.
     */
    fun plusAddressOf(recipient: String): PlusAddress? {
        val address = recipient.trim().lowercase()
        val local = address.substringBeforeLast('@', missingDelimiterValue = "")
        val domain = address.substringAfterLast('@', missingDelimiterValue = "")
        val base = local.substringBefore('+', missingDelimiterValue = "")
        val tag = local.substringAfter('+', missingDelimiterValue = "")
        val baseAddress = canonicalAddress("$base@$domain")?.takeIf { base.isNotEmpty() && tag.isNotEmpty() }

        return baseAddress?.takeIf { it in canonical }?.let { readerAddress ->
            PlusAddress(key = "${readerAddress.substringBefore('@')}+$tag@$domain", address = address, tag = tag)
        }
    }
}

private fun canonicalAddress(address: String): String? {
    val lowercase = address.trim().lowercase()
    val local = lowercase.substringBeforeLast('@', missingDelimiterValue = "")
    val domain = lowercase.substringAfterLast('@', missingDelimiterValue = "")
    if (local.isEmpty() || domain.isEmpty()) return null

    val user = if (domain in DOT_INSENSITIVE_DOMAINS) local.replace(".", "") else local
    return "$user@$domain"
}

/**
 * The domain someone registered: `mail.shop.example` and `shop.example` are one sender, `shop.co.uk` is not `co.uk`.
 */
internal fun interface RegistrableDomains {
    fun of(domain: String): String
}

/**
 * [RegistrableDomains] by the public suffix list that OkHttp carries.
 */
internal object PublicSuffixRegistrableDomains : RegistrableDomains {
    @Suppress("SwallowedException")
    override fun of(domain: String): String {
        val host = domain.trim().trimEnd('.').lowercase()
        return try {
            HttpUrl.Builder().scheme("https").host(host).build().topPrivateDomain() ?: host
        } catch (e: IllegalArgumentException) {
            host
        }
    }
}

/**
 * The domain part of [address] as its registrable domain, or `null` when it has none.
 */
internal fun RegistrableDomains.senderDomainOf(address: String?): String? {
    val domain = address?.substringAfterLast('@', missingDelimiterValue = "")?.takeIf { it.isNotBlank() }
    return domain?.let(::of)
}

/**
 * Whether [tag] names the sender at [registrableDomain]: `shop` for `shop.example`, `examplestore` for
 * `example.com`. Sites a plus address is given to are usually named in its tag, which settles who it was given to
 * without waiting for their mail to pass DMARC.
 */
internal fun tagNamesDomain(tag: String, registrableDomain: String): Boolean {
    val tagName = tag.lettersAndDigits()
    val domainName = registrableDomain.substringBefore('.').lettersAndDigits()

    return when {
        tagName.isEmpty() || domainName.isEmpty() -> false

        tagName == domainName -> true

        else -> minOf(tagName.length, domainName.length) >= MIN_CONTAINED_NAME_LENGTH &&
            (tagName.contains(domainName) || domainName.contains(tagName))
    }
}

private fun String.lettersAndDigits(): String = filter { it.isLetterOrDigit() }.lowercase()
