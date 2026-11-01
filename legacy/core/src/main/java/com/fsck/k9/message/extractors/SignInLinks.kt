package com.fsck.k9.message.extractors

import com.fsck.k9.mail.Part
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.internet.MimeUtility
import org.jsoup.Jsoup

/**
 * Subject words that say a mail is about signing in or confirming an address, in the languages the code finder
 * reads too. Only a mail with one of them is looked at for a sign-in link.
 */
private val SIGN_IN_SUBJECT = Regex(
    "(?iu)(?<!\\p{L})(?:sign[ -]?in|log[ -]?in|login|magic link|verify|verification|confirm\\p{L}*|one-time|" +
        "passwordless|access link|anmeld\\p{L}*|einlogg\\p{L}*|bestätig\\p{L}*|iniciar sesión|" +
        "inicio de sesión|verific\\p{L}*|вход\\p{L}*|войти|подтвер\\p{L}*)(?!\\p{L})",
)

/**
 * What a sign-in link's text says, all of it: `Sign in`, `Log in to Medium`, `Verify email address`,
 * `Click here to log in with this magic link`, `Yes, it's me`. Words a newsletter's footer uses too, like `Sign in`,
 * only count in a mail whose subject is about signing in.
 */
private val SIGN_IN_LINK_TEXT = Regex(
    "(?iu)^(?:" +
        "(?:(?:click|tap) here to )?(?:sign|log)[ -]?in(?: now| here)?(?: (?:to|with|on) .{1,60})?|" +
        "(?:click|tap) here to (?:sign|log)[ -]?in.{0,60}|" +
        "login(?: now| here)?(?: (?:to|with|on) .{1,60})?|" +
        ".{0,40}magic (?:sign[ -]?in |log[ -]?in |login )?link.{0,40}|" +
        "(?:verify|confirm)(?: now)?|" +
        "(?:verify|confirm)(?: my| your| this)? (?:email|e-mail|email address|e-mail address|address|account|" +
        "sign[ -]?in|log[ -]?in|login|identity|device)(?: address)?(?: now)?|" +
        "(?:yes, )?(?:it'?s|it was|this is|this was) me|" +
        "(?:jetzt )?anmelden|einloggen|(?:e-mail(?:-adresse)? |konto )?bestätigen|" +
        "iniciar sesión|(?:verificar|confirmar)(?: (?:mi |tu |su )?(?:correo|correo electrónico|cuenta|email))?|" +
        "войти|подтвердить(?: (?:почту|адрес|email|вход))?" +
        ")[.!]?$",
)

/**
 * Words in plain text right before a link that make it the sign-in link, as in
 * `Click the link below to sign in:` followed by the address on its own line.
 */
private val SIGN_IN_PHRASE = Regex(
    "(?iu)(?<!\\p{L})(?:sign[ -]?in|log[ -]?in|login|magic link|verify|confirm|anmelden|einloggen|bestätigen|" +
        "iniciar sesión|verificar|confirmar|войти|подтвердить)(?!\\p{L})",
)

private val WEB_ADDRESS = Regex("""(?i)https?://[^\s<>"'()\[\]]+""")
private val WEB_LINK = Regex("""(?i)^https?://""")
private val WHITESPACE = Regex("""\s+""")

/**
 * Words in a link that make it the way off a list or out of an account rather than into one.
 */
private val NOT_SIGN_IN_LINK = Regex("""(?i)unsubscribe|opt-?out|preferences|privacy""")

/**
 * How much plain text before a link is read for [SIGN_IN_PHRASE]: the sentence leading up to it.
 */
private const val MAX_PHRASE_DISTANCE = 160

/**
 * Link text longer than this is a paragraph that happens to be a link, not a button.
 */
private const val MAX_LINK_TEXT_LENGTH = 100

/**
 * Whether [subject] says the mail is about signing in or confirming an address, the only kind of mail
 * [findSignInLink] looks in. Cheap enough to decide whether a message's body is worth reading or downloading.
 */
fun looksLikeSignInMail(subject: String?): Boolean = subject != null && SIGN_IN_SUBJECT.containsMatchIn(subject)

/**
 * Finds the link in a sign-in or address confirmation mail that signs the reader in or confirms the address - the
 * "magic link" that many services send in place of a code - so it can be opened without searching the message for
 * it.
 *
 * Only a mail whose subject is about signing in is read, and in it only a link whose text says what it does (`Sign
 * in`, `Verify email`), or in plain text a web address right after such words. The body must be stored; a message
 * whose body has not been downloaded has no link to find.
 *
 * Says nothing about whether the sender is who they claim, nor where the link goes; callers must not offer it for a
 * message flagged as an impersonation or from a threat-listed sender, and open it through the link check.
 *
 * @return the link's address as written in the message, or `null` when there is none.
 */
fun findSignInLink(message: Part, subject: String?): String? {
    if (!looksLikeSignInMail(subject)) return null

    val html = MimeUtility.findFirstPartByMimeType(message, "text/html")?.let(MessageExtractor::getTextFromPart)
    return if (html != null) {
        findSignInLinkInHtml(html)
    } else {
        MimeUtility.findFirstPartByMimeType(message, "text/plain")
            ?.let(MessageExtractor::getTextFromPart)
            ?.let(::findSignInLinkInText)
    }
}

internal fun findSignInLinkInHtml(html: String): String? {
    return Jsoup.parse(html).select("a[href]").asSequence()
        .filter { anchor ->
            val text = anchor.text().replace(WHITESPACE, " ").trim()
            text.length <= MAX_LINK_TEXT_LENGTH && SIGN_IN_LINK_TEXT.matches(text)
        }
        .map { it.attr("href").trim() }
        .firstOrNull { WEB_LINK.containsMatchIn(it) && !NOT_SIGN_IN_LINK.containsMatchIn(it) }
}

internal fun findSignInLinkInText(text: String): String? {
    return WEB_ADDRESS.findAll(text)
        .filter { address ->
            val before = text.substring(maxOf(0, address.range.first - MAX_PHRASE_DISTANCE), address.range.first)
            SIGN_IN_PHRASE.containsMatchIn(before)
        }
        .map { it.value.trimEnd('.', ',', ';', ':', '!', '?', '>') }
        .firstOrNull { !NOT_SIGN_IN_LINK.containsMatchIn(it) }
}
