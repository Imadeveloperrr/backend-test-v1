package im.bigs.pg.external.pg

import com.fasterxml.jackson.databind.ObjectMapper
import im.bigs.pg.application.pg.port.out.PgApproveRequest
import im.bigs.pg.application.pg.port.out.PgApproveResult
import im.bigs.pg.application.pg.port.out.PgClientOutPort
import im.bigs.pg.domain.payment.PaymentStatus
import im.bigs.pg.external.pg.crypto.AesGcmCrypto
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.LocalDateTime

/**
 * TestPay PG 연동 클라이언트
 * - RestClient를 사용한 동기 HTTP 요청
 * - AES-256-GCM 암호화 사용
 *
 * 보안: 카드번호 등 민감정보는 로그에 출력하지 않음 (README 평가 기준 준수)
 */
@Component
class TestPayClient(
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
    @Value("\${testpay.api-key}") private val apiKey: String
) : PgClientOutPort {

    override fun supports(partnerId: Long): Boolean = partnerId % 2L == 0L // 0, 2, 4, ...

    override fun approve(request: PgApproveRequest): PgApproveResult {
        // 민감정보 로깅 금지: cardBin, cardLast4, 더미카드번호등 로그 출력 X

        // 1. 더미 카드번호 구성 (민감정보 최소화)
        val dummyCardNumber = "${request.cardBin}000000${request.cardLast4}"

        // 2. 평문 요청 생성
        val plainRequest = TestPayPlainRequest(
            cardNumber = dummyCardNumber,
            birthDate = "19900101",
            expiry = "1227",
            password = "12",
            amount = request.amount.toLong()
        )

        // 3. JSON 직렬화 및 암호화
        val plainJson = objectMapper.writeValueAsString(plainRequest)
        val encrypted = AesGcmCrypto.encrypt(plainJson, apiKey)

        // 4. API 요청
        val response = restClient.post()
            .uri("/api/v1/pay/credit-card")
            .header("API-KEY", apiKey)
            .body(TestPayEncRequest(enc = encrypted))
            .retrieve()
            .body(TestPayResponse::class.java)
            ?: throw IllegalStateException("TestPay API response is null")

        // 5. PgApproveResult로 변환
        return PgApproveResult(
            approvalCode = response.approvalCode,
            approvedAt = LocalDateTime.parse(response.approvedAt),
            status = PaymentStatus.valueOf(response.status)
        )
    }
}
