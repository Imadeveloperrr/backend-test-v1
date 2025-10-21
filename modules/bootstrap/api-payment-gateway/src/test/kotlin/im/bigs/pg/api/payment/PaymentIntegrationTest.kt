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
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
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
}