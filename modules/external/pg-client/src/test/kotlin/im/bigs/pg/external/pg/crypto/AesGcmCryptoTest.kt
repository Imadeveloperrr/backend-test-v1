package im.bigs.pg.external.pg.crypto

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AesGcmCryptoTest {

    private val apiKey = "test-api-key-1234567890"

    @Test
    fun `암복호화 정확성 검증`() {
        val plainText = "Hello, TestPay!"
        val encrypted = AesGcmCrypto.encrypt(plainText, apiKey)
        val decrypted = AesGcmCrypto.decrypt(encrypted, apiKey)

        assertEquals(plainText, decrypted)
    }

    @Test
    fun `IV 랜덤성 검증 - 같은 평문도 매번 다른 암호문`() {
        val plainText = "Same text"
        val encrypted1 = AesGcmCrypto.encrypt(plainText, apiKey)
        val encrypted2 = AesGcmCrypto.encrypt(plainText, apiKey)

        assertNotEquals(encrypted1, encrypted2)
    }

    @Test
    fun `잘못된 API 키로 복호화 시 예외 발생`() {
        val plainText = "Secret message"
        val encrypted = AesGcmCrypto.encrypt(plainText, apiKey)

        assertThrows<Exception> {
            AesGcmCrypto.decrypt(encrypted, "wrong-api-key")
        }
    }

    @Test
    fun `변조된 암호문 복호화 시 예외 발생`() {
        val plainText = "Original"
        val encrypted = AesGcmCrypto.encrypt(plainText, apiKey)
        val tampered = encrypted.dropLast(5) + "AAAAA"  // 마지막 5자 변조

        assertThrows<Exception> {
            AesGcmCrypto.decrypt(tampered, apiKey)
        }
    }
}