package im.bigs.pg.external.pg

import com.fasterxml.jackson.databind.ObjectMapper
import im.bigs.pg.application.pg.port.out.PgApproveRequest
import im.bigs.pg.domain.payment.PaymentStatus
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TestPayClientTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var testPayClient: TestPayClient
    private val objectMapper = ObjectMapper()
    private val apiKey = "test-api-key-1234567890"

    @BeforeEach
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()

        val baseUrl = mockServer.url("/").toString()
        val restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Content-Type", "application/json")
            .build()

        // 실제 생성자 시그니처: TestPayClient(RestClient, ObjectMapper, String)
        testPayClient = TestPayClient(restClient, objectMapper, apiKey)
    }

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    @DisplayName("결제 승인 성공 시 모든 필수 필드를 반환해야 한다")
    fun `결제 승인 성공 케이스`() {
        // Given: TestPayResponse 실제 구조에 맞는 성공 응답 Mock
        val mockResponse = """
            {
                "approvalCode": "APPROVAL-12345",
                "approvedAt": "2024-01-01T12:00:00",
                "maskedCardLast4": "4242",
                "amount": 10000,
                "status": "APPROVED"
            }
        """.trimIndent()
        mockServer.enqueue(MockResponse()
            .setBody(mockResponse)
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json"))

        // When: 결제 승인 요청
        val request = PgApproveRequest(
            partnerId = 2L,  // 짝수 partnerId (TestPayClient는 짝수만 지원)
            amount = BigDecimal("10000"),
            cardBin = "123456",
            cardLast4 = "4242",
            productName = "Test Product"
        )
        val result = testPayClient.approve(request)

        // Then: 응답 검증
        assertEquals("APPROVAL-12345", result.approvalCode)
        assertNotNull(result.approvedAt)
        assertEquals(PaymentStatus.APPROVED, result.status)
    }

    @Test
    @DisplayName("API 호출 시 암호화된 페이로드가 전송되어야 한다")
    fun `암호화된 요청 페이로드 검증`() {
        // Given: 성공 응답 Mock
        val mockResponse = """
            {
                "approvalCode": "APPROVAL-12345",
                "approvedAt": "2024-01-01T12:00:00",
                "maskedCardLast4": "4242",
                "amount": 10000,
                "status": "APPROVED"
            }
        """.trimIndent()
        mockServer.enqueue(MockResponse()
            .setBody(mockResponse)
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json"))

        // When: 결제 승인 요청
        val request = PgApproveRequest(
            partnerId = 2L,
            amount = BigDecimal("10000"),
            cardBin = "123456",
            cardLast4 = "4242",
            productName = "Test Product"
        )
        testPayClient.approve(request)

        // Then: 요청 본문 구조 검증
        val recordedRequest = mockServer.takeRequest()
        val requestBody = objectMapper.readTree(recordedRequest.body.readUtf8())

        // 실제 요청 본문은 {"enc": "암호문"} 형태
        assertTrue(requestBody.has("enc"))
        val encryptedPayload = requestBody.get("enc").asText()

        // URL-safe Base64 형식인지 확인 (-, _ 포함 가능)
        assertNotNull(encryptedPayload)
        assertTrue(encryptedPayload.matches(Regex("^[A-Za-z0-9_-]+$")))
    }

    @Test
    @DisplayName("API-KEY 헤더가 요청에 포함되어야 한다")
    fun `API-KEY 헤더 검증`() {
        // Given: 성공 응답 Mock
        val mockResponse = """
            {
                "approvalCode": "APPROVAL-12345",
                "approvedAt": "2024-01-01T12:00:00",
                "maskedCardLast4": "4242",
                "amount": 10000,
                "status": "APPROVED"
            }
        """.trimIndent()
        mockServer.enqueue(MockResponse()
            .setBody(mockResponse)
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json"))

        // When: 결제 승인 요청
        val request = PgApproveRequest(
            partnerId = 2L,
            amount = BigDecimal("10000"),
            cardBin = "123456",
            cardLast4 = "4242",
            productName = "Test Product"
        )
        testPayClient.approve(request)

        // Then: API-KEY 헤더 검증
        val recordedRequest = mockServer.takeRequest()
        assertEquals(apiKey, recordedRequest.getHeader("API-KEY"))
    }

    @Test
    @DisplayName("partnerId가 짝수일 때만 지원해야 한다")
    fun `supports 메서드 검증`() {
        // 실제 구현: partnerId % 2L == 0L (짝수만 지원)

        // partnerId가 짝수면 true
        assertEquals(true, testPayClient.supports(0L))
        assertEquals(true, testPayClient.supports(2L))
        assertEquals(true, testPayClient.supports(4L))
        assertEquals(true, testPayClient.supports(1000L))

        // partnerId가 홀수면 false
        assertEquals(false, testPayClient.supports(1L))
        assertEquals(false, testPayClient.supports(3L))
        assertEquals(false, testPayClient.supports(999L))
    }
}