package com.fsck.k9.contacts.bimi

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import org.junit.Test

class DnsTxtResponseTest {

    @Test
    fun `should read a single TXT record`() {
        val response = dnsResponse(answers = listOf(txtAnswer("v=BIMI1; l=https://example.com/logo.svg")))

        assertThat(parseTxtRecords(response)).containsExactly("v=BIMI1; l=https://example.com/logo.svg")
    }

    @Test
    fun `should join the character strings of one record`() {
        // A record longer than 255 bytes arrives split, and the pieces belong together with no separator.
        val response = dnsResponse(answers = listOf(txtAnswer("v=BIMI1; l=https://", "example.com/logo.svg")))

        assertThat(parseTxtRecords(response)).containsExactly("v=BIMI1; l=https://example.com/logo.svg")
    }

    @Test
    fun `should read every TXT record at a name`() {
        val response = dnsResponse(answers = listOf(txtAnswer("first"), txtAnswer("second")))

        assertThat(parseTxtRecords(response)).containsExactly("first", "second")
    }

    @Test
    fun `should skip records of other types`() {
        // A name can hold more than TXT, and anything else has to be stepped over rather than misread.
        val response = dnsResponse(answers = listOf(answer(type = 1, data = byteArrayOf(1, 2, 3, 4)), txtAnswer("txt")))

        assertThat(parseTxtRecords(response)).containsExactly("txt")
    }

    @Test
    fun `should follow a compressed name`() {
        val response = dnsResponse(answers = listOf(txtAnswer("compressed", useCompressedName = true)))

        assertThat(parseTxtRecords(response)).containsExactly("compressed")
    }

    @Test
    fun `a response with no answers should yield nothing`() {
        assertThat(parseTxtRecords(dnsResponse(answers = emptyList()))).isEmpty()
    }

    @Test
    fun `a truncated response should yield nothing rather than throwing`() {
        // The bytes come off the network, so a short or hostile response is ordinary input to handle.
        val response = dnsResponse(answers = listOf(txtAnswer("cut short")))

        assertThat(parseTxtRecords(response.copyOf(response.size - 5))).isEmpty()
    }

    @Test
    fun `an empty response should yield nothing`() {
        assertThat(parseTxtRecords(ByteArray(0))).isEmpty()
    }

    @Test
    fun `a record claiming more data than it holds should yield nothing`() {
        val response = dnsResponse(answers = listOf(answer(type = DNS_TYPE_TXT, data = byteArrayOf(100, 65, 66))))

        assertThat(parseTxtRecords(response)).containsExactly("")
    }

    private fun txtAnswer(vararg strings: String, useCompressedName: Boolean = false): ByteArray {
        val data = strings.fold(ByteArray(0)) { acc, string ->
            val bytes = string.toByteArray()
            acc + byteArrayOf(bytes.size.toByte()) + bytes
        }

        return answer(type = DNS_TYPE_TXT, data = data, useCompressedName = useCompressedName)
    }

    private fun answer(type: Int, data: ByteArray, useCompressedName: Boolean = false): ByteArray {
        val name = if (useCompressedName) {
            // 0xC00C: a pointer to the question's name, which is what real resolvers emit.
            byteArrayOf(0xC0.toByte(), 0x0C)
        } else {
            encodeName("example.com")
        }

        return name +
            byteArrayOf((type shr 8).toByte(), type.toByte()) +
            byteArrayOf(0, 1) +
            byteArrayOf(0, 0, 0, 60) +
            byteArrayOf((data.size shr 8).toByte(), data.size.toByte()) +
            data
    }

    private fun dnsResponse(answers: List<ByteArray>): ByteArray {
        val header = byteArrayOf(
            0x12, 0x34,
            0x81.toByte(), 0x80.toByte(),
            0, 1,
            0, answers.size.toByte(),
            0, 0,
            0, 0,
        )
        val question = encodeName("default._bimi.example.com") + byteArrayOf(0, 16) + byteArrayOf(0, 1)

        return answers.fold(header + question) { acc, answer -> acc + answer }
    }

    private fun encodeName(name: String): ByteArray {
        return name.split('.')
            .fold(ByteArray(0)) { acc, label ->
                acc + byteArrayOf(label.length.toByte()) + label.toByteArray()
            } + byteArrayOf(0)
    }
}
