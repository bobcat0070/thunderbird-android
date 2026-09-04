package com.fsck.k9.contacts.bimi

import android.util.Base64
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import net.thunderbird.core.android.testing.RobolectricTest
import org.junit.Test

private const val TAG_IA5_STRING = 0x16
private const val TAG_SEQUENCE = 0x30
private const val TAG_OCTET_STRING = 0x04

/**
 * The bytes walked here sit inside a CA-signed certificate, but the certificate is named by the sender's own
 * domain and a malformed one has to fail rather than throw: the walk runs while a message list row is being
 * drawn, for senders nobody has vetted.
 */
class LogotypeExtensionTest : RobolectricTest() {

    @Test
    fun `should collect a string at the top level`() {
        val bytes = der(TAG_IA5_STRING, "https://example.com/logo.svg".toByteArray())

        assertThat(collectTaggedStrings(bytes, TAG_IA5_STRING))
            .containsExactly("https://example.com/logo.svg")
    }

    @Test
    fun `should descend into constructed values`() {
        val bytes = der(TAG_SEQUENCE, der(TAG_SEQUENCE, der(TAG_IA5_STRING, "found".toByteArray())))

        assertThat(collectTaggedStrings(bytes, TAG_IA5_STRING)).containsExactly("found")
    }

    @Test
    fun `should collect every match, in order`() {
        val strings = der(TAG_IA5_STRING, "one".toByteArray()) + der(TAG_IA5_STRING, "two".toByteArray())

        assertThat(collectTaggedStrings(der(TAG_SEQUENCE, strings), TAG_IA5_STRING))
            .containsExactly("one", "two")
    }

    @Test
    fun `should read a long form length`() {
        // 200 bytes needs the long form, which is a different path through the length reader.
        val value = "x".repeat(200)

        assertThat(collectTaggedStrings(der(TAG_IA5_STRING, value.toByteArray()), TAG_IA5_STRING))
            .containsExactly(value)
    }

    @Test
    fun `should stop at nesting deeper than the walk allows`() {
        // A certificate nested hundreds deep would otherwise recurse until the stack gave out. The cap is
        // what makes that a shrug rather than a crash.
        var bytes = der(TAG_IA5_STRING, "buried".toByteArray())
        repeat(times = 500) { bytes = der(TAG_SEQUENCE, bytes) }

        assertThat(collectTaggedStrings(bytes, TAG_IA5_STRING)).isEmpty()
    }

    @Test
    fun `should ignore a value that claims more bytes than are there`() {
        val bytes = byteArrayOf(TAG_IA5_STRING.toByte(), 100, 'a'.code.toByte(), 'b'.code.toByte())

        assertThat(collectTaggedStrings(bytes, TAG_IA5_STRING)).isEmpty()
    }

    @Test
    fun `should ignore a length large enough to overflow the offset`() {
        // 0x7fffffff is a positive length, so the "does it fit" check is all that stands between it and a
        // read past the end of the array - and the check is itself an addition that can wrap.
        val bytes = byteArrayOf(TAG_IA5_STRING.toByte(), 0x84.toByte(), 0x7f, -1, -1, -1, 0)

        assertThat(collectTaggedStrings(bytes, TAG_IA5_STRING)).isEmpty()
    }

    @Test
    fun `should ignore an overflowing length under a constructed value`() {
        // The same length taken down the other branch of the walk, where it becomes a range copy rather than
        // a string read.
        val bytes = byteArrayOf(TAG_SEQUENCE.toByte(), 0x84.toByte(), 0x7f, -1, -1, -1, 0)

        assertThat(collectTaggedStrings(bytes, TAG_IA5_STRING)).isEmpty()
    }

    @Test
    fun `should ignore an indefinite length`() {
        // Legal in BER, not in DER, and there is nothing to guess at: this walk has no end marker to find.
        val bytes = byteArrayOf(TAG_SEQUENCE.toByte(), 0x80.toByte(), TAG_IA5_STRING.toByte(), 1, 'a'.code.toByte())

        assertThat(collectTaggedStrings(bytes, TAG_IA5_STRING)).isEmpty()
    }

    @Test
    fun `should ignore a truncated length`() {
        assertThat(collectTaggedStrings(byteArrayOf(TAG_IA5_STRING.toByte()), TAG_IA5_STRING)).isEmpty()
    }

    @Test
    fun `an empty extension should yield nothing`() {
        assertThat(collectTaggedStrings(ByteArray(0), TAG_IA5_STRING)).isEmpty()
    }

    @Test
    fun `should keep what it read before hitting something malformed`() {
        // A partly readable extension is worth what was read; discarding a valid mark because something
        // after it was damaged would lose more than it protects.
        val good = der(TAG_IA5_STRING, "good".toByteArray())
        val truncated = byteArrayOf(TAG_IA5_STRING.toByte(), 100, 'a'.code.toByte())

        assertThat(collectTaggedStrings(good + truncated, TAG_IA5_STRING)).containsExactly("good")
    }

    @Test
    fun `should unwrap a short form octet string`() {
        assertThat(unwrapOctetString(der(TAG_OCTET_STRING, byteArrayOf(1, 2, 3)))?.toList())
            .isEqualTo(listOf<Byte>(1, 2, 3))
    }

    @Test
    fun `should unwrap a long form octet string`() {
        val contents = ByteArray(200) { 7 }

        assertThat(unwrapOctetString(der(TAG_OCTET_STRING, contents))?.toList()).isEqualTo(contents.toList())
    }

    @Test
    fun `should not unwrap something that is not an octet string`() {
        assertThat(unwrapOctetString(der(TAG_SEQUENCE, byteArrayOf(1)))).isNull()
    }

    @Test
    fun `should not unwrap an empty or truncated value`() {
        assertThat(unwrapOctetString(ByteArray(0))).isNull()
        assertThat(unwrapOctetString(byteArrayOf(TAG_OCTET_STRING.toByte()))).isNull()
        assertThat(unwrapOctetString(byteArrayOf(TAG_OCTET_STRING.toByte(), 3))).isNull()
    }

    @Test
    fun `should extract an svg from a data uri`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"/>""".toByteArray()

        assertThat(extractLogotypeSvg(logotypeExtension(svg))?.toList()).isEqualTo(svg.toList())
    }

    @Test
    fun `should decompress a gzipped svg`() {
        // How marks are usually carried. The data URI does not say so, which is why compression has to be
        // detected from the bytes themselves.
        val svg = "<svg/>".toByteArray()

        assertThat(extractLogotypeSvg(logotypeExtension(gzip(svg)))?.toList()).isEqualTo(svg.toList())
    }

    @Test
    fun `should ignore a mark hosted elsewhere`() {
        // RFC 3709 allows a mark to live at a URL. Fetching one would mean a request to a host the sender's
        // own certificate names, so an external mark is simply not shown.
        val bytes = der(TAG_IA5_STRING, "https://example.com/logo.svg".toByteArray())

        assertThat(extractLogotypeSvg(bytes)).isNull()
    }

    @Test
    fun `should ignore a data uri that is not valid base64`() {
        val bytes = der(TAG_IA5_STRING, "data:image/svg+xml;base64,!!!not base64!!!".toByteArray())

        assertThat(extractLogotypeSvg(bytes)).isNull()
    }

    @Test
    fun `should ignore bytes that claim to be gzipped but are not`() {
        assertThat(extractLogotypeSvg(logotypeExtension(byteArrayOf(0x1f, -0x75, 0, 0, 0)))).isNull()
    }

    @Test
    fun `an extension with no strings should yield no mark`() {
        assertThat(extractLogotypeSvg(der(TAG_SEQUENCE, byteArrayOf()))).isNull()
    }

    @Test
    fun `a malformed extension should yield no mark rather than throwing`() {
        assertThat(extractLogotypeSvg(byteArrayOf(TAG_IA5_STRING.toByte(), 100, 'a'.code.toByte()))).isNull()
    }

    private fun logotypeExtension(image: ByteArray): ByteArray {
        val uri = "data:image/svg+xml;base64," + Base64.encodeToString(image, Base64.NO_WRAP)

        return der(TAG_SEQUENCE, der(TAG_SEQUENCE, der(TAG_IA5_STRING, uri.toByteArray())))
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }

        return output.toByteArray()
    }

    /**
     * Encodes one DER value, choosing the short or long length form the way an encoder would.
     */
    private fun der(tag: Int, contents: ByteArray): ByteArray {
        val length = when {
            contents.size < 0x80 -> byteArrayOf(contents.size.toByte())
            contents.size < 0x100 -> byteArrayOf(0x81.toByte(), contents.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (contents.size shr 8).toByte(), contents.size.toByte())
        }

        return byteArrayOf(tag.toByte()) + length + contents
    }
}
