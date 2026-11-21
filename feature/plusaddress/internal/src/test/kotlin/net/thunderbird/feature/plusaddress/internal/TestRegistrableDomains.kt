package net.thunderbird.feature.plusaddress.internal

/**
 * The last two labels of a domain. OkHttp reads its public suffix list from Android assets, which unit tests do not
 * have; the domains in the tests need nothing more.
 */
internal object TestRegistrableDomains : RegistrableDomains {
    override fun of(domain: String): String = domain.lowercase().trimEnd('.').split('.').takeLast(2).joinToString(".")
}
