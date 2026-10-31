package com.fsck.k9.message.extractors

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

class VerificationCodesTest {
    @Test
    fun `a code named in the subject should be found`() {
        assertThat(findVerificationCode("Your verification code is 482913", null)).isEqualTo("482913")
    }

    @Test
    fun `a code before the word that names it should be found`() {
        assertThat(findVerificationCode("482913 is your Instagram code", null)).isEqualTo("482913")
    }

    @Test
    fun `a code at the end of a sentence should be found`() {
        val text = "Hi Alex, use this code to sign in: 123456. It expires in 10 minutes."

        assertThat(findVerificationCode("Sign in to Example", text)).isEqualTo("123456")
    }

    @Test
    fun `the subject should be read before the preview`() {
        assertThat(findVerificationCode("Code: 1111 2222", "Your code is 987654")).isEqualTo("11112222")
    }

    @Test
    fun `a code split in two groups should be copied without the separator`() {
        assertThat(findVerificationCode(null, "Your security code: 123 456")).isEqualTo("123456")
        assertThat(findVerificationCode(null, "Your security code: 123-456")).isEqualTo("123456")
    }

    @Test
    fun `a code after a letter and hyphen should be found without its prefix`() {
        assertThat(findVerificationCode("G-482913 is your Google verification code", null)).isEqualTo("482913")
    }

    @Test
    fun `codes in other languages and compounds should be found`() {
        assertThat(findVerificationCode("Ihr Bestätigungscode lautet 552901", null)).isEqualTo("552901")
        assertThat(findVerificationCode("Tu código de verificación es 773311", null)).isEqualTo("773311")
        assertThat(findVerificationCode("Ваш код: 4417", null)).isEqualTo("4417")
    }

    @Test
    fun `a PIN should be found`() {
        assertThat(findVerificationCode(null, "Your one-time PIN is 8812")).isEqualTo("8812")
    }

    @Test
    fun `the code nearest to its word should be taken`() {
        val text = "Order 55512345 shipped. Your verification code is 246810."

        assertThat(findVerificationCode(null, text)).isEqualTo("246810")
    }

    @Test
    fun `a number without a word naming it as a code should give nothing`() {
        assertThat(findVerificationCode("Your order 55512345 has shipped", "Delivery by 1430 on Friday")).isNull()
    }

    @Test
    fun `a number far from the word should give nothing`() {
        val text = "We have updated how verification works for your account. Your account number is 12345678."

        assertThat(findVerificationCode(null, text)).isNull()
    }

    @Test
    fun `shop and postal codes should give nothing`() {
        assertThat(findVerificationCode("Use promo code 4521 at checkout", null)).isNull()
        assertThat(findVerificationCode("Postcode 10115 confirmed", null)).isNull()
        assertThat(findVerificationCode("Enter your zip code 90210 to see stores", null)).isNull()
    }

    @Test
    fun `phone numbers prices times and percentages should give nothing`() {
        assertThat(findVerificationCode(null, "Questions about your PIN? Call 1-800-555-1234")).isNull()
        assertThat(findVerificationCode(null, "Verification fee: $1250.00")).isNull()
        assertThat(findVerificationCode(null, "Verify by 10:30 today")).isNull()
        assertThat(findVerificationCode(null, "Code SAVE2050 takes 2050% off")).isNull()
    }

    @Test
    fun `a year should give nothing`() {
        assertThat(findVerificationCode("Verify your account by March 2027", null)).isNull()
    }

    @Test
    fun `a number inside a word or link should give nothing`() {
        assertThat(findVerificationCode(null, "Verification: see example.com/v/123456 or ref ABC123456")).isNull()
    }

    @Test
    fun `nothing should be found in empty text`() {
        assertThat(findVerificationCode(null, null)).isNull()
        assertThat(findVerificationCode("", "")).isNull()
    }
}
