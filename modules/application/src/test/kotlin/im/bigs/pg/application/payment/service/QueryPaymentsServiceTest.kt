package im.bigs.pg.application.payment.service

import im.bigs.pg.application.payment.port.`in`.QueryFilter
import im.bigs.pg.application.payment.port.out.PaymentOutPort
import im.bigs.pg.application.payment.port.out.PaymentPage
import im.bigs.pg.application.payment.port.out.PaymentSummaryProjection
import im.bigs.pg.domain.payment.Payment
import im.bigs.pg.domain.payment.PaymentStatus
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class 결제조회서비스Test {
    private val paymentOutPort = mockk<PaymentOutPort>()
    private val service = QueryPaymentsService(paymentOutPort)

    @Test
    @DisplayName("첫 페이지 조회 시 커서 없이 조회")
    fun `첫 페이지 조회`() {
        val items = listOf(
            Payment(id = 1L, partnerId = 1L, amount = BigDecimal("10000"), appliedFeeRate = BigDecimal("0.03"),
                feeAmount = BigDecimal("300"), netAmount = BigDecimal("9700"), cardBin = "123456", cardLast4 = "4242",
                approvalCode = "APP1", approvedAt = LocalDateTime.of(2024,1,1,12,0), status = PaymentStatus.APPROVED,
                createdAt = LocalDateTime.of(2024,1,1,12,0))
        )
        every { paymentOutPort.findBy(any()) } returns PaymentPage(
            items = items, hasNext = true,
            nextCursorCreatedAt = LocalDateTime.of(2024,1,1,12,0), nextCursorId = 1L
        )
        every { paymentOutPort.summary(any()) } returns PaymentSummaryProjection(
            count = 10L, totalAmount = BigDecimal("100000"), totalNetAmount = BigDecimal("97000")
        )

        val filter = QueryFilter(partnerId = 1L, limit = 10)
        val result = service.query(filter)

        assertEquals(1, result.items.size)
        assertTrue(result.hasNext)
        assertNotNull(result.nextCursor)
        assertEquals(10L, result.summary.count)
    }

    @Test
    @DisplayName("마지막 페이지 조회 시 hasNext = false, nextCursor = null")
    fun `마지막 페이지 조회`() {
        val items = listOf(
            Payment(id = 1L, partnerId = 1L, amount = BigDecimal("10000"), appliedFeeRate = BigDecimal("0.03"),
                feeAmount = BigDecimal("300"), netAmount = BigDecimal("9700"), cardBin = "123456", cardLast4 = "4242",
                approvalCode = "APP1", approvedAt = LocalDateTime.of(2024,1,1,12,0), status = PaymentStatus.APPROVED,
                createdAt = LocalDateTime.of(2024,1,1,12,0))
        )
        every { paymentOutPort.findBy(any()) } returns PaymentPage(
            items = items, hasNext = false, nextCursorCreatedAt = null, nextCursorId = null
        )
        every { paymentOutPort.summary(any()) } returns PaymentSummaryProjection(
            count = 1L, totalAmount = BigDecimal("10000"), totalNetAmount = BigDecimal("9700")
        )

        val filter = QueryFilter(partnerId = 1L, limit = 10)
        val result = service.query(filter)

        assertEquals(1, result.items.size)
        assertFalse(result.hasNext)
        assertNull(result.nextCursor)
    }

    @Test
    @DisplayName("summary는 items와 동일한 필터 적용 (커서 제외)")
    fun `summary 정합성 검증`() {
        every { paymentOutPort.findBy(any()) } returns PaymentPage(
            items = emptyList(), hasNext = false, nextCursorCreatedAt = null, nextCursorId = null
        )
        every { paymentOutPort.summary(any()) } returns PaymentSummaryProjection(
            count = 100L, totalAmount = BigDecimal("1000000"), totalNetAmount = BigDecimal("970000")
        )

        val filter = QueryFilter(partnerId = 1L, status = "APPROVED", limit = 10)
        val result = service.query(filter)

        // 페이지는 비었지만 summary는 전체 집합 대상
        assertEquals(0, result.items.size)
        assertEquals(100L, result.summary.count)
        assertEquals(BigDecimal("1000000"), result.summary.totalAmount)
        assertEquals(BigDecimal("970000"), result.summary.totalNetAmount)
    }

    @Test
    @DisplayName("잘못된 커서는 무시하고 첫 페이지 조회")
    fun `잘못된 커서 처리`() {
        every { paymentOutPort.findBy(any()) } returns PaymentPage(
            items = emptyList(), hasNext = false, nextCursorCreatedAt = null, nextCursorId = null
        )
        every { paymentOutPort.summary(any()) } returns PaymentSummaryProjection(
            count = 0L, totalAmount = BigDecimal.ZERO, totalNetAmount = BigDecimal.ZERO
        )

        val filter = QueryFilter(partnerId = 1L, cursor = "invalid-cursor-123", limit = 10)
        val result = service.query(filter)

        // 잘못된 커서는 무시하고 정상 처리
        assertEquals(0, result.items.size)
    }

    @Test
    @DisplayName("커서 인코딩/디코딩 왕복 검증")
    fun `커서 인코딩_디코딩 왕복`() {
        val testCreatedAt = LocalDateTime.of(2024,1,1,12,30,45)
        val testId = 12345L

        every { paymentOutPort.findBy(any()) } returns PaymentPage(
            items = emptyList(), hasNext = true,
            nextCursorCreatedAt = testCreatedAt, nextCursorId = testId
        )
        every { paymentOutPort.summary(any()) } returns PaymentSummaryProjection(
            count = 0L, totalAmount = BigDecimal.ZERO, totalNetAmount = BigDecimal.ZERO
        )

        val filter = QueryFilter(partnerId = 1L, limit = 10)
        val result = service.query(filter)

        // nextCursor를 다시 사용해서 조회 시 정상 디코딩
        assertNotNull(result.nextCursor)

        val filter2 = QueryFilter(partnerId = 1L, cursor = result.nextCursor, limit = 10)
        every { paymentOutPort.findBy(any()) } returns PaymentPage(
            items = emptyList(), hasNext = false, nextCursorCreatedAt = null, nextCursorId = null
        )
        val result2 = service.query(filter2)

        // 두 번째 조회도 정상 처리
        assertEquals(0, result2.items.size)
    }
}