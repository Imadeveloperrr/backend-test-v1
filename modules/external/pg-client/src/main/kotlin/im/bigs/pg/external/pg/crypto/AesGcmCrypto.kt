package im.bigs.pg.external.pg.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM Utillity
 * PgClient API 연동을 위한 암호화를 제공
 *
 *  - Algorithm : AES-256-GCM
 *  - Key : SHA-256(API-KEY) -> 32Byte
 *  - IV : Random 12Byte (Base64URL Decoding)
 *  - Tag : 16Byte (Base64URL Decoding)
 */
object AesGcmCrypto {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_SIZE = 128

    /**
     * 평문을 AES-256-GCM으로 암호화하여 Base64URL 인코딩된 문자열을 반환
     *
     * @param plainText 암호화할 평문
     * @param apiKey API Key (SHA-256 해싱되어 AES Key로 사용)
     * @return Base64URL 인코딩된 암호문 (IV + 암호문 + Tag)
     */
    fun encrypt(plainText: String, apiKey: String): String {
        // 1. API-KEY를 SHA-256으로 해싱하여 32바이트 키 생성
        val key = deriveKey(apiKey)

        // 2. 랜덤 IV 생성 (12 Byte)
        val iv = ByteArray(IV_SIZE)
        SecureRandom().nextBytes(iv)

        // 3. AES-GCM Cipher 초기화
        val cipher = Cipher.getInstance(ALGORITHM)
        val secretKey = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(TAG_SIZE, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

        // 4. 암호화 수행 (암호문 + Tag가 함께 반환)
        val cipherTextWithTag = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // 5. IV + 암호문 + Tag를 합쳐서 Base64URL 인코딩
        val combined = iv + cipherTextWithTag
        return Base64.getUrlEncoder().withoutPadding().encodeToString(combined)
    }

    /**
     * Base64URL 인코딩된 암호문을 복호화하여 평문을 반환
     *
     * @param encrypted Base64URL 인코딩된 암호문 (IV + 암호문 + Tag)
     * @param apiKey API 키 (SHA-256 해싱되어 AES 키로 사용됨)
     * @return 복호화된 평문
     * @throws IllegalArgumentException 복호화 실패 또는 데이터 무결성 검증 실패
     */
    fun decrypt(encrypted: String, apiKey: String): String {
        // 1. Base64URL 디코딩
        val combined = Base64.getUrlDecoder().decode(encrypted)

        // 2. IV 추출 (첫 12 Byte)
        if (combined.size < IV_SIZE) {
            throw IllegalArgumentException("Invalid encrypted data")
        }
        val iv = combined.copyOfRange(0, IV_SIZE)

        // 3. 암호문 + Tag 추출 (나머지 바이트)
        val cipherTextWithTag = combined.copyOfRange(IV_SIZE, combined.size)

        // 4. API-KEY를 SHA-256으로 해싱하여 32바이트 키 생성
        val key = deriveKey(apiKey)

        // 5. AES-GCM Cipher 초기화
        val cipher = Cipher.getInstance(ALGORITHM)
        val secretKey = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(TAG_SIZE, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        // 6. 복호화 수행 (Tag 검증 포함)
        val plainTextBytes = cipher.doFinal(cipherTextWithTag)

        return String(plainTextBytes, Charsets.UTF_8)
    }

    /**
     * API-KEY를 SHA-256으로 해싱하여 32바이트 AES키를 생성
     *
     * @param apiKey 원본 API 키
     * @return 32바이트 AES 키
     */
    private fun deriveKey(apiKey: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(apiKey.toByteArray(Charsets.UTF_8))
    }

    /**
     *   1. IV는 절대 재사용 금지: 매번 SecureRandom()으로 생성
     *   2. API-KEY는 안전하게 보관: 환경변수나 설정 파일에 저장
     *   3. Tag 검증 실패는 무조건 거부: 데이터가 변조된 것
     *   4. 예외 처리 필수: decrypt() 시 여러 예외 발생 가능
     */
}
