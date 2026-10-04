package net.thunderbird.feature.impersonation.internal

private const val ACE_PREFIX = "xn--"

/**
 * Second-level names under which a country code registry hands out domains, as in `example.co.uk`. Without the
 * public suffix list this is an approximation, but it covers where nearly all mail comes from, and getting it wrong
 * only makes two domains look more alike or less alike than they are.
 */
private val SECOND_LEVEL_NAMES = setOf("ac", "co", "com", "edu", "gov", "ne", "net", "or", "org")
private const val COUNTRY_CODE_LENGTH = 2

/**
 * The domain someone registered, which is what a lookalike imitates: `mail.example.com` and `example.com` are one
 * sender, `example.co.uk` is not the same as `co.uk`.
 */
internal fun registrableDomain(domain: String): String {
    val labels = domain.trim().trimEnd('.').lowercase().split('.').filter { it.isNotEmpty() }
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
 * The first label of a registrable domain, the part that carries the name: `example` of `example.co.uk`.
 */
internal fun nameLabel(registrableDomain: String): String = registrableDomain.substringBefore('.')

/**
 * The part of a registrable domain after its name label: `com`, or `co.uk`.
 */
internal fun suffixOf(
    registrableDomain: String,
): String = registrableDomain.substringAfter('.', missingDelimiterValue = "")

/**
 * The domain as people read it: every `xn--` label decoded, so a name written in another alphabet can be compared
 * letter by letter. A label that does not decode is kept as it was.
 */
internal fun unicodeDomain(domain: String): String {
    return domain.split('.').joinToString(".") { label -> decodePunycodeLabel(label) ?: label }
}

/**
 * Decodes one `xn--` label (RFC 3492). Labels without the prefix are returned unchanged.
 *
 * @return the decoded label, or `null` when it is not valid Punycode.
 */
@Suppress("CyclomaticComplexMethod", "ReturnCount", "LoopWithTooManyJumpStatements", "MagicNumber")
internal fun decodePunycodeLabel(label: String): String? {
    if (!label.startsWith(ACE_PREFIX, ignoreCase = true)) return label

    val input = label.substring(ACE_PREFIX.length).lowercase()
    val base = 36
    val tMin = 1
    val tMax = 26

    val output = mutableListOf<Int>()
    val delimiter = input.lastIndexOf('-')
    if (delimiter > 0) {
        for (character in input.substring(0, delimiter)) {
            if (character.code >= 0x80) return null
            output.add(character.code)
        }
    }

    var codePoint = 0x80
    var index = 0
    var bias = 72
    var position = if (delimiter > 0) delimiter + 1 else 0

    while (position < input.length) {
        val oldIndex = index
        var weight = 1
        var k = base

        while (true) {
            if (position >= input.length) return null
            val digit = punycodeDigit(input[position++]) ?: return null
            if (digit > (Int.MAX_VALUE - index) / weight) return null
            index += digit * weight

            val threshold = when {
                k <= bias -> tMin
                k >= bias + tMax -> tMax
                else -> k - bias
            }
            if (digit < threshold) break
            if (weight > Int.MAX_VALUE / (base - threshold)) return null
            weight *= base - threshold
            k += base
        }

        val length = output.size + 1
        bias = adaptBias(index - oldIndex, length, isFirst = oldIndex == 0)
        if (index / length > Int.MAX_VALUE - codePoint) return null
        codePoint += index / length
        index %= length
        if (codePoint > 0x10FFFF) return null

        output.add(index, codePoint)
        index++
    }

    return buildString {
        for (point in output) {
            if (point > 0xFFFF) {
                val offset = point - 0x10000
                append(Char(0xD800 + (offset shr 10)))
                append(Char(0xDC00 + (offset and 0x3FF)))
            } else {
                append(Char(point))
            }
        }
    }
}

@Suppress("MagicNumber")
private fun punycodeDigit(character: Char): Int? = when (character) {
    in 'a'..'z' -> character - 'a'
    in '0'..'9' -> character - '0' + 26
    else -> null
}

@Suppress("MagicNumber")
private fun adaptBias(delta: Int, length: Int, isFirst: Boolean): Int {
    var scaled = if (isFirst) delta / 700 else delta / 2
    scaled += scaled / length

    var k = 0
    while (scaled > ((36 - 1) * 26) / 2) {
        scaled /= 36 - 1
        k += 36
    }

    return k + (36 - 1 + 1) * scaled / (scaled + 38)
}

/**
 * Letters that look like a Latin letter in the fonts mail is read in, mapped to that letter.
 *
 * Cyrillic and Greek supply most of them; digits and a few symbols stand in for letters in plain ASCII lookalikes.
 * Deliberately short: only shapes that actually fool a reader belong here, and every addition is a chance for two
 * honest domains to look the same.
 */
private val CONFUSABLES = mapOf(
    // Digits and symbols written for letters.
    '0' to 'o', '1' to 'l', '3' to 'e', '5' to 's', '|' to 'l', '!' to 'i',
    // Cyrillic.
    'а' to 'a', 'в' to 'b', 'е' to 'e', 'ё' to 'e', 'һ' to 'h', 'і' to 'i', 'ї' to 'i', 'ј' to 'j', 'к' to 'k',
    'м' to 'm', 'н' to 'h', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'т' to 't', 'у' to 'y', 'х' to 'x', 'ѕ' to 's',
    'ԁ' to 'd', 'ԛ' to 'q', 'ԝ' to 'w', 'ү' to 'y',
    // Greek.
    'α' to 'a', 'β' to 'b', 'ε' to 'e', 'η' to 'n', 'ι' to 'i', 'κ' to 'k', 'ν' to 'v', 'ο' to 'o', 'ρ' to 'p',
    'τ' to 't', 'υ' to 'u', 'χ' to 'x',
    // Latin letters that pass for plainer ones.
    'ı' to 'i', 'ɡ' to 'g', 'ɩ' to 'i', 'ł' to 'l',
)

/**
 * Letter pairs that read as one letter at a glance.
 */
private val CONFUSABLE_PAIRS = listOf("rn" to "m", "vv" to "w", "cl" to "d")

/**
 * What a name looks like rather than what it is: two names with the same skeleton are hard to tell apart, which is
 * the whole point of a lookalike domain.
 *
 * Confusable letters are folded together, hyphens dropped, and doubled letters collapsed, so `paypa1`, `pаypal`
 * (with a Cyrillic `а`), `pay-pal` and `paypall` all come out as `paypal`'s skeleton. `l`, `i` and `1` end up as
 * one letter, because in many fonts they are.
 */
internal fun skeleton(name: String): String {
    var folded = name.lowercase().map { CONFUSABLES[it] ?: it }.joinToString("")
    for ((pair, single) in CONFUSABLE_PAIRS) {
        folded = folded.replace(pair, single)
    }
    folded = folded.replace("-", "").replace('l', 'i')

    return buildString {
        for (character in folded) {
            if (isEmpty() || last() != character) append(character)
        }
    }
}

private enum class Script { LATIN, CYRILLIC, GREEK, ARMENIAN, OTHER }

@Suppress("MagicNumber")
private fun scriptOf(character: Char): Script? = when {
    !character.isLetter() -> null
    character.code < 0x250 -> Script.LATIN
    character.code in 0x370..0x3FF -> Script.GREEK
    character.code in 0x400..0x52F -> Script.CYRILLIC
    character.code in 0x530..0x58F -> Script.ARMENIAN
    else -> Script.OTHER
}

/**
 * Whether one label of a domain mixes Latin letters with Cyrillic, Greek or Armenian ones - the alphabets with
 * letters that pass for Latin. A domain written wholly in one of them is ordinary; one that mixes them exists to
 * look like something it is not.
 */
internal fun hasMixedScriptLabel(unicodeDomain: String): Boolean {
    return unicodeDomain.split('.').any { label ->
        val scripts = label.mapNotNull(::scriptOf).toSet()
        Script.LATIN in scripts && scripts.any { it == Script.CYRILLIC || it == Script.GREEK || it == Script.ARMENIAN }
    }
}
