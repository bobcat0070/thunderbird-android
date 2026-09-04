package net.thunderbird.core.preference.websiteicon

const val WEBSITE_ICON_SETTINGS_DEFAULT_IS_ENABLED = false

/**
 * Whether to fall back to the icon of the sender domain's website when nothing better is available.
 *
 * Most senders publish no brand indicator: of a handful checked by hand, PayPal and LinkedIn did, while One
 * Medical, E*TRADE and Walmart did not. Their websites all have an icon, so this is what closes the gap
 * between a mailbox where a few senders are recognisable and one where most are.
 *
 * Off by default, and deliberately so: the lookup goes to a third party and tells it which domains this
 * device gets mail from. That is a reasonable trade for someone who wants sender pictures and a poor one to
 * make on their behalf - the same reason Gravatar is off by default and DNS-over-HTTPS was not used for BIMI.
 *
 * A website icon is also worth less than a brand indicator: nobody vouched for it, and it is not published as
 * a mail identity at all. It is shown with the same "unverified" badge a self-asserted BIMI logo carries, and
 * only for mail that passed DMARC, so it cannot be borrowed by a message that merely claims to be from the
 * domain.
 */
data class WebsiteIconSettings(
    val isEnabled: Boolean = WEBSITE_ICON_SETTINGS_DEFAULT_IS_ENABLED,
)
