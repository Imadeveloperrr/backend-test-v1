package im.bigs.pg.external.pg

/**
 * TestPay API Request (암호화된 Payload)
 */
data class TestPayEncRequest(
    val enc: String
)

/**
 * TestPay API 평문 요청 데이터
 * 실제 API 스펙에 맞춘 필드
 */
data class TestPayPlainRequest(
    val cardNumber: String, // 16자리 (cardBin + "000000" + cardLast4) 앞 은행 식별번호
    val birthDate: String,
    val expiry: String,
    val password: String,
    val amount: Long
)

/**
 * TestPay API Response (Plain - Not Encrypted)
 */
data class TestPayResponse(
    val approvalCode: String,
    val approvedAt: String, // ISO8601 format
    val maskedCardLast4: String,
    val amount: Long,
    val status: String // "APPROVED" or "REJECTED"
)