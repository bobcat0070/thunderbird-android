package com.fsck.k9.message.extractors

import com.fsck.k9.mail.Part
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.internet.MimeUtility
import org.jsoup.Jsoup

/**
 * What a service's security mail says happened to the reader's account there.
 */
enum class SecurityAlert {
    /** Someone signed in, from a new device or place, or tried to. */
    SIGN_IN,

    /** A password reset was asked for: the mail carries the way to set a new one. */
    PASSWORD_RESET,

    /** The password was changed or reset. */
    PASSWORD_CHANGED,

    /** Two-factor sign-in was turned off, or a recovery address, phone or passkey changed. */
    SECURITY_SETTINGS,

    /** A security alert or notice that says no more in its subject. */
    OTHER,
}

/**
 * Words that put the reader's own words into a pattern: optional `your`, `my` or `the` before a noun.
 */
private const val ARTICLE = "(?:your |my |the |a )?"

private val PASSWORD_CHANGED = Regex(
    "(?iu)(?<!\\p{L})(?:" +
        "pass(?:word|code) (?:has been |was |is |successfully |just )*(?:changed|updated)|" +
        "pass(?:word|code) (?:has been |was |successfully |just )+reset|" +
        "password change(?:d)?|(?:changed|updated) ${ARTICLE}password|" +
        "password reset (?:was |is )?(?:successful\\p{L}*|complete\\p{L}*|done)|" +
        "passwort (?:wurde )?(?:geändert|zurückgesetzt|aktualisiert)|passwortänderung|kennwort (?:wurde )?geändert|" +
        "contraseña (?:ha sido |fue |se ha )?(?:cambiad\\p{L}*|actualizad\\p{L}*|restablecid\\p{L}*)|" +
        "cambio de contraseña|" +
        "пароль (?:был )?(?:изменён|изменен|сброшен|обновлён|обновлен)|изменени\\p{L}* пароля" +
        ")(?!\\p{L})",
)

private val PASSWORD_RESET = Regex(
    "(?iu)(?<!\\p{L})(?:" +
        "password reset|reset ${ARTICLE}password|forgot ${ARTICLE}password|password recovery|" +
        "recover ${ARTICLE}(?:account|password)|set a new password|" +
        "passwort (?:zurücksetzen|vergessen)|kennwort (?:zurücksetzen|vergessen)|" +
        "restablec\\p{L}* (?:tu |su |la )?contraseña|recupera\\p{L}* (?:tu |su |la )?contraseña|" +
        "сброс\\p{L}* парол\\p{L}*|восстановлени\\p{L}* парол\\p{L}*" +
        ")(?!\\p{L})",
)

private val SECURITY_SETTINGS = Regex(
    "(?iu)(?<!\\p{L})(?:" +
        "(?:two|2)[ -]?(?:factor|step)(?: authentication| verification| sign[ -]?in)? " +
        "(?:(?:was|has been|is) )?(?:turned off|disabled|removed|deactivated)|" +
        "(?:multi[ -]factor|2fa|mfa)(?: authentication)? (?:(?:was|has been|is) )?(?:turned off|disabled|removed)|" +
        "(?:turned off|disabled) ${ARTICLE}(?:two|2)[ -]?(?:factor|step)|" +
        "(?:recovery|backup) (?:email|e-mail|phone|phone number|address) " +
        "(?:(?:was|has been) )?(?:changed|added|updated|removed)|" +
        "(?:email|e-mail) address (?:(?:was|has been) )?changed|" +
        "security (?:info|information|settings) (?:(?:was|were|has been|have been) )?(?:changed|updated)|" +
        "(?:passkey|security key) (?:(?:was|has been) )?(?:added|removed)|" +
        "zwei-faktor\\p{L}* (?:wurde )?(?:deaktiviert|ausgeschaltet)|" +
        "(?:verificación|autenticación) en dos pasos desactivad\\p{L}*|" +
        "двухфакторн\\p{L}* (?:аутентификаци\\p{L}* )?отключен\\p{L}*" +
        ")(?!\\p{L})",
)

private val SIGN_IN = Regex(
    "(?iu)(?<!\\p{L})(?:" +
        "new (?:sign[ -]?in|log[ -]?in|login)|" +
        "(?:sign(?:ed)?[ -]?in|log(?:ged)?[ -]?in|login) (?:from|on|with) (?:a )?new|" +
        "new device (?:sign(?:ed)?[ -]?in|log(?:ged)?[ -]?in|login)|" +
        "(?:unusual|suspicious|unrecogni[sz]ed|unknown) (?:sign[ -]?in|log[ -]?in|login|activity|device)|" +
        "(?:sign[ -]?in|log[ -]?in|login) attempt|someone (?:signed|logged) in|" +
        "neue anmeldung|ungewöhnliche anmeldung|anmeldung (?:von|auf) einem neuen|" +
        "nuevo inicio de sesión|inicio de sesión (?:nuevo|inusual|sospechoso)|" +
        "новый вход|вход с нового|подозрительн\\p{L}* вход\\p{L}*" +
        ")(?!\\p{L})",
)

private val OTHER_ALERT = Regex(
    "(?iu)(?<!\\p{L})(?:" +
        "security (?:alert|notification|notice|warning)|critical security|" +
        "sicherheitswarnung|sicherheitshinweis|alerta de seguridad|aviso de seguridad|" +
        "оповещение (?:о )?безопасности|предупреждение (?:о )?безопасности" +
        ")(?!\\p{L})",
)

/**
 * Words that a breach notice puts in its subject, or in its text when the subject says only that it is a notice.
 */
private val BREACH = Regex(
    "(?iu)(?<!\\p{L})(?:" +
        "data (?:security )?(?:breach|incident|leak)|security (?:breach|incident)|cyber ?security incident|" +
        "breach of ${ARTICLE}(?:personal |customer )?(?:data|information)|notice of (?:a )?data|" +
        "your (?:personal )?(?:information|data|details) (?:may have been|was|were|has been|have been) " +
        "(?:exposed|accessed|compromised|involved|affected|leaked|stolen|obtained|acquired)|" +
        "(?:you'?ve|you have) been pwned|pwned on|" +
        "datenleck|datenpanne|sicherheitsvorfall|" +
        "brecha de (?:datos|seguridad)|violación de (?:datos|seguridad)|incidente de seguridad|" +
        "утечк\\p{L}* (?:персональных )?данных|инцидент\\p{L}* безопасности" +
        ")(?!\\p{L})",
)

/**
 * Subject words of a notice that leaves the breach to its text: `Important notice`, `An update about your account`.
 */
private val NOTICE_SUBJECT = Regex(
    "(?iu)(?<!\\p{L})(?:notice|notification|important|incident|security|update (?:about|on|regarding)|" +
        "regarding your|wichtig|hinweis|aviso|importante|уведомлени\\p{L}*|важн\\p{L}*)(?!\\p{L})",
)

/**
 * How much of a message's text is read for a breach: notices say so in their first paragraphs.
 */
private const val MAX_TEXT_LENGTH = 4000

/**
 * Says what a service's security mail is about, from its subject - where Google, Microsoft, Apple, banks and shops
 * name it - so it can be told from the routine mail of the same sender.
 *
 * The subject alone is read: a message's text mentions passwords and sign-ins in footers and help sections that
 * say nothing about the reader's account. A changed password is told before a reset, so `Your password was reset`
 * is a change and `Reset your password` a request.
 *
 * A reply or a forward is a conversation about such mail, not the alert itself, and a subject that carries a sign-in
 * code is a code mail, which [findVerificationCode] offers to copy; neither is an alert.
 *
 * Says nothing about whether the sender is who they claim; such mail is what phishing dresses up as, so callers
 * that give it more attention must first know that it passed its sender check.
 *
 * @return what the mail says happened, or `null` when the subject is not about the account's security.
 */
fun findSecurityAlert(subject: String?): SecurityAlert? {
    val text = subject?.take(MAX_TEXT_LENGTH)?.takeIf { it.isNotBlank() && !isReplyOrForward(it) } ?: return null
    return when {
        findVerificationCode(text, null) != null -> null
        PASSWORD_CHANGED.containsMatchIn(text) -> SecurityAlert.PASSWORD_CHANGED
        PASSWORD_RESET.containsMatchIn(text) -> SecurityAlert.PASSWORD_RESET
        SECURITY_SETTINGS.containsMatchIn(text) -> SecurityAlert.SECURITY_SETTINGS
        SIGN_IN.containsMatchIn(text) -> SecurityAlert.SIGN_IN
        OTHER_ALERT.containsMatchIn(text) -> SecurityAlert.OTHER
        else -> null
    }
}

/**
 * Whether a message tells the reader that a service lost their data: a breach notice, or a Have I Been Pwned alert.
 *
 * The subject is read first. Many notices give only `Important notice` or `An update about your account` there and
 * the breach in their text, so for such a subject [text] is read too - but not for a newsletter, which writes about
 * other people's breaches.
 *
 * @param text the message's text, plain or as read from its HTML.
 * @param isNewsletter whether the message was filed as a newsletter.
 */
fun isBreachNotice(subject: String?, text: String?, isNewsletter: Boolean): Boolean {
    val subjectText = subject?.take(MAX_TEXT_LENGTH).orEmpty()
    if (isReplyOrForward(subjectText)) return false

    return BREACH.containsMatchIn(subjectText) ||
        !isNewsletter && text != null && NOTICE_SUBJECT.containsMatchIn(subjectText) &&
        BREACH.containsMatchIn(text.take(MAX_TEXT_LENGTH))
}

/**
 * The prefixes mail programs put before the subject of a reply or a forward, in the languages they come in.
 */
private val REPLY_OR_FORWARD_PREFIX = Regex(
    "(?i)^\\s*(?:re|fw|fwd|aw|wg|sv|vs|tr|rv|odp|antw|ref)\\s*(?:\\[\\d+])?\\s*:",
)

private fun isReplyOrForward(subject: String): Boolean = REPLY_OR_FORWARD_PREFIX.containsMatchIn(subject)

/**
 * Whether [message] tells the reader that a service lost their data, as [isBreachNotice] decides from its subject
 * and the start of its text. The body must be stored; without it only the subject is read.
 */
fun findBreachNotice(message: Part, subject: String?, isNewsletter: Boolean): Boolean {
    return isBreachNotice(subject, messageText(message, MAX_TEXT_LENGTH), isNewsletter)
}

/**
 * The start of a message's text: its plain-text part, or else the words of its HTML part.
 */
internal fun messageText(message: Part, maxLength: Int): String? {
    val plainText = MimeUtility.findFirstPartByMimeType(message, "text/plain")?.let(MessageExtractor::getTextFromPart)
    val text = plainText
        ?: MimeUtility.findFirstPartByMimeType(message, "text/html")?.let(MessageExtractor::getTextFromPart)
            ?.let { Jsoup.parse(it).text() }
    return text?.take(maxLength)
}
