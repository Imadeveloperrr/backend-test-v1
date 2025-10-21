package im.bigs.pg.api.payment

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Transactional  // 각 테스트 후 롤백으로 DB 격리
class PaymentIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    @DisplayName("결제 생성 후 조회 시 통계가 정확히 일치해야 한다")
    fun `결제 생성 후 조회 시 통계 정합성`() {
        // Given: 결제 3건 생성 (partnerId=1, MockPgClient 사용 - 빠르고 결정적)
        val amounts = listOf(10000, 20000, 30000)
        amounts.forEach { amount ->
            val request = """
                {
                    "partnerId": 1,
                    "amount": $amount,
                    "cardBin": "123456",
                    "cardLast4": "4242",
                    "productName": "Test Product"
                }
            """.trimIndent()

            mockMvc.perform(
                post("/api/v1/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request)
            ).andExpect(status().isOk)
        }

        // When: partnerId=1 결제 전체 조회
        val result = mockMvc.perform(get("/api/v1/payments?partnerId=1&limit=100"))
            .andExpect(status().isOk)
            .andReturn()

        // Then: items와 summary 정합성 확인
        val response = objectMapper.readTree(result.response.contentAsString)
        val items = response["items"]
        val summary = response["summary"]

        // items 개수와 summary.count 일치
        assertEquals(3, items.size())
        assertEquals(3, summary["count"].asLong())

        // items의 amount 합계와 summary.totalAmount 일치
        val itemsAmountSum = items.sumOf { it["amount"].asLong() }
        assertEquals(60000L, itemsAmountSum)
        assertEquals(60000L, summary["totalAmount"].asLong())

        // items의 netAmount 합계와 summary.totalNetAmount 일치
        val itemsNetAmountSum = items.sumOf { it["netAmount"].asLong() }
        assertEquals(itemsNetAmountSum, summary["totalNetAmount"].asLong())
    }

    @Test
    @DisplayName("수수료 정책이 정확히 적용되어 계산되어야 한다")
    fun `수수료 정책 적용 검증`() {
        // Given: partnerId=1 (MockPgClient, 수수료 정책 적용)
        val request = """
            {
                "partnerId": 1,
                "amount": 10000,
                "cardBin": "123456",
                "cardLast4": "4242",
                "productName": "Test"
            }
        """.trimIndent()

        // When: 결제 생성
        val result = mockMvc.perform(
            post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request)
        )
            // Then: 수수료 정확성 검증
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.amount").value(10000))
            .andExpect(jsonPath("$.appliedFeeRate").exists())
            .andExpect(jsonPath("$.feeAmount").exists())
            .andExpect(jsonPath("$.netAmount").exists())
            .andReturn()

        // 수수료 계산 검증 (amount - feeAmount = netAmount)
        val response = objectMapper.readTree(result.response.contentAsString)
        val amount = response["amount"].asLong()
        val feeAmount = response["feeAmount"].asLong()
        val netAmount = response["netAmount"].asLong()
        assertEquals(amount - feeAmount, netAmount)
    }

    @Test
    @DisplayName("커서 페이징이 정렬 키 기준으로 올바르게 동작해야 한다")
    fun `커서 페이징 동작 검증`() {
        // Given: 결제 5건 생성
        repeat(5) {
            val request = """
                {
                    "partnerId": 1,
                    "amount": 10000,
                    "cardBin": "123456",
                    "cardLast4": "4242",
                    "productName": "Test"
                }
            """.trimIndent()
            mockMvc.perform(
                post("/api/v1/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request)
            ).andExpect(status().isOk)
        }

        // When: 첫 페이지 조회 (limit=2)
        val firstPage = mockMvc.perform(get("/api/v1/payments?partnerId=1&limit=2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.hasNext").value(true))
            .andExpect(jsonPath("$.nextCursor").exists())
            .andReturn()

        val firstResponse = objectMapper.readTree(firstPage.response.contentAsString)
        val nextCursor = firstResponse["nextCursor"].asText()
        val firstItems = firstResponse["items"]

        // Then: nextCursor로 두 번째 페이지 조회
        val secondPage = mockMvc.perform(get("/api/v1/payments?partnerId=1&cursor=$nextCursor&limit=2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.hasNext").value(true))
            .andReturn()

        val secondResponse = objectMapper.readTree(secondPage.response.contentAsString)
        val secondItems = secondResponse["items"]

        // 첫 페이지와 두 번째 페이지의 항목이 중복되지 않음
        val firstIds = firstItems.map { it["id"].asLong() }.toSet()
        val secondIds = secondItems.map { it["id"].asLong() }.toSet()
        assertTrue(firstIds.intersect(secondIds).isEmpty())  // 중복 없음
    }

    @Test
    @DisplayName("필터 조합이 올바르게 동작해야 한다")
    fun `복합 필터 조합 검증`() {
        // Given: partnerId=1, APPROVED 상태 결제 생성
        val request = """
            {
                "partnerId": 1,
                "amount": 10000,
                "cardBin": "123456",
                "cardLast4": "4242",
                "productName": "Test"
            }
        """.trimIndent()
        mockMvc.perform(
            post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request)
        ).andExpect(status().isOk)

        // When: partnerId=1, status=APPROVED 필터로 조회
        mockMvc.perform(get("/api/v1/payments?partnerId=1&status=APPROVED&limit=10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isArray)
            .andExpect(jsonPath("$.summary.count").isNumber)

        // When: partnerId=1, status=CANCELED 필터로 조회 (결과 없음 - APPROVED만 생성했으므로)
        mockMvc.perform(get("/api/v1/payments?partnerId=1&status=CANCELED&limit=10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(0))
            .andExpect(jsonPath("$.summary.count").value(0))
    }

    @Test
    @DisplayName("제휴사별로 다른 수수료 정책이 적용되어야 한다")
    fun `제휴사별 다른 수수료 정책 E2E 검증`() {
        // Given: Partner 1, 3 각각 결제 생성 (동일 금액)
        // - Partner 1: MockPgClient (홀수, 2.35% + 0원)
        // - Partner 3: MockPgClient (홀수, 3.50% + 50원)
        val amount = 10000

        // Partner 1: MockPgClient (홀수) - 빠르고 결정적
        val request1 = """
            {
                "partnerId": 1,
                "amount": $amount,
                "cardBin": "123456",
                "cardLast4": "4242",
                "productName": "Test"
            }
        """.trimIndent()
        val result1 = mockMvc.perform(
            post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request1)
        )
            .andExpect(status().isOk)
            .andReturn()

        // Partner 3: MockPgClient (홀수) - 빠르고 결정적
        val request3 = """
            {
                "partnerId": 3,
                "amount": $amount,
                "cardBin": "123456",
                "cardLast4": "4242",
                "productName": "Test"
            }
        """.trimIndent()
        val result3 = mockMvc.perform(
            post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request3)
        )
            .andExpect(status().isOk)
            .andReturn()

        // Then: 각 파트너의 수수료가 다르게 적용되었는지 확인
        val response1 = objectMapper.readTree(result1.response.contentAsString)
        val response3 = objectMapper.readTree(result3.response.contentAsString)

        val feeAmount1 = response1["feeAmount"].asLong()
        val feeAmount3 = response3["feeAmount"].asLong()
        val netAmount1 = response1["netAmount"].asLong()
        val netAmount3 = response3["netAmount"].asLong()

        // 각 파트너의 정산금 검증 (amount - feeAmount = netAmount)
        assertEquals(amount - feeAmount1, netAmount1)
        assertEquals(amount - feeAmount3, netAmount3)

        // 수수료 정책이 다르게 적용되었는지 확인
        // Partner 1: 10000 * 0.0235 = 235원
        // Partner 3: 10000 * 0.035 + 50 = 400원
        assertEquals(235L, feeAmount1)
        assertEquals(400L, feeAmount3)
    }
}
