package net.thunderbird.feature.trips.internal

import android.content.Context
import androidx.core.content.edit
import java.util.concurrent.atomic.AtomicInteger
import net.thunderbird.feature.trips.TravelSenders

/**
 * Airlines, hotels, rentals, cruise lines and booking sites that send booking mail, each with the kind of booking it
 * sends when it sends only one; `null` for a booking site or a resort that sends several kinds.
 */
internal val BUILT_IN_TRAVEL_SENDERS: Map<String, ReservationType?> = buildMap {
    listOf(
        "aa.com", "aeromexico.com", "airfrance.com", "aircanada.com", "alaskaair.com", "allegiantair.com",
        "avianca.com", "ba.com", "britishairways.com", "delta.com", "emirates.com", "flybreeze.com",
        "flyfrontier.com", "hawaiianairlines.com", "iberia.com", "jetblue.com", "klm.com", "lufthansa.com",
        "qatarairways.com", "southwest.com", "spirit.com", "suncountry.com", "united.com", "volaris.com",
        "westjet.com",
    ).forEach { put(it, ReservationType.FLIGHT) }

    listOf(
        "accor.com", "airbnb.com", "asrlodging.com", "bestwestern.com", "booking.com", "caesars.com",
        "choicehotels.com", "fourseasons.com", "hilton.com", "hotels.com", "hyatt.com", "ihg.com", "marriott.com",
        "mgmresorts.com", "radissonhotels.com", "sonder.com", "vacasa.com", "venetianlasvegas.com", "vrbo.com",
        "wyndhamhotels.com", "wynnlasvegas.com",
    ).forEach { put(it, ReservationType.LODGING) }

    listOf(
        "carnival.com", "celebritycruises.com", "disneycruise.com", "hollandamerica.com", "msccruises.com",
        "ncl.com", "princess.com", "rccl.com", "rcgdocumentation.com", "royalcaribbean.com", "vikingcruises.com",
        "virginvoyages.com",
    ).forEach { put(it, ReservationType.CRUISE) }

    listOf(
        "alamo.com", "avis.com", "budget.com", "enterprise.com", "hertz.com", "nationalcar.com", "sixt.com",
        "thrifty.com", "turo.com",
    ).forEach { put(it, ReservationType.CAR_RENTAL) }

    listOf("exploretock.com", "opentable.com", "resy.com", "sevenrooms.com")
        .forEach { put(it, ReservationType.RESTAURANT) }

    listOf(
        "axs.com",
        "eventbrite.com",
        "frontgatetickets.com",
        "livenation.com",
        "ponorez.com",
        "seatgeek.com",
        "stubhub.com",
        "ticketmaster.com",
    ).forEach { put(it, ReservationType.EVENT) }

    listOf(
        "agoda.com", "cbtravel.com", "concursolutions.com", "costcotravel.com", "disney-email.com",
        "disneydestinations.com", "disneyland.com", "disneyworld.com", "expedia.com", "hopper.com", "kayak.com",
        "orbitz.com", "priceline.com", "travelocity.com", "trip.com", "universalorlando.com",
    ).forEach { put(it, null) }
}

/**
 * Mail providers anyone can have an address at; listing one would take in everyone's mail.
 */
private val MAIL_PROVIDERS = setOf(
    "aol.com", "comcast.net", "cox.net", "gmail.com", "gmx.com", "googlemail.com", "hotmail.com", "icloud.com",
    "live.com", "mac.com", "me.com", "msn.com", "outlook.com", "proton.me", "protonmail.com", "yahoo.com",
    "ymail.com", "zoho.com",
)

/**
 * Second-level names under which a country code registry hands out domains, as in `example.co.uk`.
 */
private val SECOND_LEVEL_NAMES = setOf("ac", "co", "com", "edu", "gov", "ne", "net", "or", "org")
private const val COUNTRY_CODE_LENGTH = 2

private val DOMAIN = Regex("^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")
private const val MAX_DOMAIN_LENGTH = 253

/**
 * How many marked mails are kept; the oldest marks are dropped past it.
 */
private const val MAX_MARKED_MAILS = 300

/**
 * [domain] lower-cased, when it is a plain domain name such as `example.com`; anything else is refused, as the
 * list ends up in a database query.
 */
internal fun normalizedDomain(domain: String): String? =
    domain.trim().trimEnd('.').lowercase().takeIf { it.length <= MAX_DOMAIN_LENGTH && DOMAIN.matches(it) }

/**
 * The domain someone registered: `t.flyfrontier.com` is `flyfrontier.com`, `example.co.uk` stays as it is.
 */
internal fun registrableDomain(domain: String): String {
    val labels = domain.split('.')
    val count = if (labels.size >= 3 && labels[labels.size - 2] in SECOND_LEVEL_NAMES &&
        labels.last().length == COUNTRY_CODE_LENGTH
    ) {
        3
    } else {
        2
    }
    return labels.takeLast(count).joinToString(".")
}

/**
 * The listed domain that [address] is at, itself or a subdomain of it.
 */
internal fun listedDomainOf(address: String, domains: Set<String>): String? {
    val domain = normalizedDomain(address.substringAfterLast('@', missingDelimiterValue = ""))
    return generateSequence(domain) {
        it.substringAfter('.', missingDelimiterValue = "").takeIf { rest -> '.' in rest }
    }
        .firstOrNull { it in domains }
}

/**
 * A listed sender, for the screen that shows the list.
 */
internal data class ListedSender(
    val domain: String,
    val type: ReservationType?,
    val isAddedByReader: Boolean,
)

/**
 * The travel sender list as Trips reads it.
 */
internal interface TravelSenderStore : TravelSenders {
    /**
     * Changes whenever the list or the marks change, so a screen can tell its bookings need reading again.
     */
    val version: Int

    fun listed(): List<ListedSender>

    fun markedMessageIds(): Set<String>

    /**
     * The kind of booking the listed sender [address] is at sends, when it sends only one.
     */
    fun typeOf(address: String): ReservationType?

    fun isListedSender(address: String): Boolean
}

/**
 * The built-in senders, less those the reader removed, and the senders and mails the reader added, kept on the
 * device only.
 */
internal class SharedPreferencesTravelSenderStore(context: Context) : TravelSenderStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val changes = AtomicInteger()

    override val version: Int
        get() = changes.get()

    override fun domainOf(address: String): String? {
        val domain = normalizedDomain(address.substringAfterLast('@', missingDelimiterValue = "")) ?: return null
        return registrableDomain(domain).takeIf { it !in MAIL_PROVIDERS }
    }

    override fun isListed(domain: String): Boolean = normalizedDomain(domain) in domains()

    override fun setListed(domain: String, isListed: Boolean) {
        val normalized = normalizedDomain(domain) ?: return
        val added = stringSet(KEY_ADDED)
        val removed = stringSet(KEY_REMOVED)
        val isBuiltIn = normalized in BUILT_IN_TRAVEL_SENDERS

        if (isListed) {
            if (isBuiltIn) removed -= normalized else added += normalized
        } else {
            if (isBuiltIn) removed += normalized else added -= normalized
        }

        preferences.edit {
            putStringSet(KEY_ADDED, added)
            putStringSet(KEY_REMOVED, removed)
        }
        changes.incrementAndGet()
    }

    override fun isMarked(messageId: String): Boolean = messageId in markedMessageIds()

    override fun setMarked(messageId: String, isMarked: Boolean) {
        if (messageId.isBlank()) return
        val marked = preferences.getString(KEY_MARKED, null)?.lines()?.filter { it.isNotBlank() }.orEmpty()
        val updated = (marked - messageId).let { if (isMarked) it + messageId else it }.takeLast(MAX_MARKED_MAILS)

        preferences.edit { putString(KEY_MARKED, updated.joinToString("\n")) }
        changes.incrementAndGet()
    }

    override fun listed(): List<ListedSender> {
        val added = stringSet(KEY_ADDED)
        return domains().map { domain ->
            ListedSender(domain = domain, type = BUILT_IN_TRAVEL_SENDERS[domain], isAddedByReader = domain in added)
        }.sortedBy { it.domain }
    }

    override fun markedMessageIds(): Set<String> =
        preferences.getString(KEY_MARKED, null)?.lines()?.filter { it.isNotBlank() }?.toSet().orEmpty()

    override fun typeOf(address: String): ReservationType? =
        listedDomainOf(address, domains())?.let { BUILT_IN_TRAVEL_SENDERS[it] }

    override fun isListedSender(address: String): Boolean = listedDomainOf(address, domains()) != null

    private fun domains(): Set<String> =
        BUILT_IN_TRAVEL_SENDERS.keys - stringSet(KEY_REMOVED) + stringSet(KEY_ADDED)

    private fun stringSet(key: String): MutableSet<String> =
        preferences.getStringSet(key, null).orEmpty().toMutableSet()

    private companion object {
        const val PREFERENCES_NAME = "trips"
        const val KEY_ADDED = "added_senders"
        const val KEY_REMOVED = "removed_senders"

        // Message-IDs one per line; a header line cannot hold a line break.
        const val KEY_MARKED = "marked_mail"
    }
}
