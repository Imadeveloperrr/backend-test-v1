package im.bigs.pg.application.payment.service

import im.bigs.pg.application.partner.port.out.FeePolicyOutPort
import im.bigs.pg.application.partner.port.out.PartnerOutPort
import im.bigs.pg.application.payment.port.`in`.PaymentCommand
import im.bigs.pg.application.payment.port.out.PaymentOutPort
import im.bigs.pg.application.pg.port.out.PgApproveRequest
import im.bigs.pg.application.pg.port.out.PgApproveResult
import im.bigs.pg.application.pg.port.out.PgClientOutPort
import im.bigs.pg.domain.partner.FeePolicy
import im.bigs.pg.domain.partner.Partner
import im.bigs.pg.domain.payment.Payment
import im.bigs.pg.domain.payment.PaymentStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.DisplayName
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class 결제서비스Test {
    private val partnerRepo = mockk<PartnerOutPort>()
    private val feeRepo = mockk<FeePolicyOutPort>()
    private val paymentRepo = mockk<PaymentOutPort>()
    private val pgClient = object : PgClientOutPort {
        override fun supports(partnerId: Long) = true
        override fun approve(request: PgApproveRequest) =
            PgApproveResult("APPROVAL-123", LocalDateTime.of(2024,1,1,0,0), PaymentStatus.APPROVED)
    }

    @Test
    @DisplayName("결제 시 수수료 정책을 적용하고 저장해야 한다")
    fun `결제 시 수수료 정책을 적용하고 저장해야 한다`() {
        val service = PaymentService(partnerRepo, feeRepo, paymentRepo, listOf(pgClient))
        every { partnerRepo.findById(1L) } returns Partner(1L, "TEST", "Test", true)
        every { feeRepo.findEffectivePolicy(1L, any()) } returns FeePolicy(
            id = 10L, partnerId = 1L,
            effectiveFrom =
                LocalDateTime.ofInstant(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC),
            percentage = BigDecimal("0.0300"), fixedFee = BigDecimal("100")
        )
        val savedSlot = slot<Payment>()
        every { paymentRepo.save(capture(savedSlot)) } answers {
            savedSlot.captured.copy(id = 99L) }

        // ✅ cardBin, productName 추가 (완전한 테스트 데이터)
        val cmd = PaymentCommand(
            partnerId = 1L,
            amount = BigDecimal("10000"),
            cardBin = "123456",         // 추가
            cardLast4 = "4242",
            productName = "Test Product" // 추가
        )
        val res = service.pay(cmd)

        assertEquals(99L, res.id)
        assertEquals(BigDecimal("400"), res.feeAmount)
        assertEquals(BigDecimal("9600"), res.netAmount)
        assertEquals(PaymentStatus.APPROVED, res.status)
    }

    @Test
    @DisplayName("존재하지 않는 Partner로 결제 시 예외 발생")
    fun `존재하지 않는 Partner로 결제 시 예외 발생`() {
        val service = PaymentService(partnerRepo, feeRepo, paymentRepo, listOf(pgClient))
        every { partnerRepo.findById(999L) } returns null

        val cmd = PaymentCommand(partnerId = 999L, amount = BigDecimal("10000"), cardBin = "123456", cardLast4 = "4242", productName = "Test")

        assertFailsWith<IllegalArgumentException> {
            service.pay(cmd)
        }
    }

    @Test
    @DisplayName("비활성화된 Partner로 결제 시 예외 발생")
    fun `비활성화된 Partner로 결제 시 예외 발생`() {
        val service = PaymentService(partnerRepo, feeRepo, paymentRepo, listOf(pgClient))
        every { partnerRepo.findById(1L) } returns Partner(1L, "TEST", "Test", active = false)

        val cmd = PaymentCommand(partnerId = 1L, amount = BigDecimal("10000"), cardBin = "123456", cardLast4 = "4242", productName = "Test")

        assertFailsWith<IllegalArgumentException> {
            service.pay(cmd)
        }
    }

    @Test
    @DisplayName("지원하지 않는 PG 클라이언트 시 예외 발생")
    fun `지원하지 않는 PG 클라이언트 시 예외 발생`() {
        val unsupportedPg = object : PgClientOutPort {
            override fun supports(partnerId: Long) = false
            override fun approve(request: PgApproveRequest) =
                throw UnsupportedOperationException("Should not be called")
        }
        val service = PaymentService(partnerRepo, feeRepo, paymentRepo, listOf(unsupportedPg))
        every { partnerRepo.findById(1L) } returns Partner(1L, "TEST", "Test", true)

        val cmd = PaymentCommand(partnerId = 1L, amount = BigDecimal("10000"), cardBin = "123456", cardLast4 = "4242", productName = "Test")

        assertFailsWith<IllegalStateException> {
            service.pay(cmd)
        }
    }

    @Test
    @DisplayName("수수료 정책이 없을 때 예외 발생")
    fun `수수료 정책이 없을 때 예외 발생`() {
        val service = PaymentService(partnerRepo, feeRepo, paymentRepo, listOf(pgClient))
        every { partnerRepo.findById(1L) } returns Partner(1L, "TEST", "Test", true)
        every { feeRepo.findEffectivePolicy(1L, any()) } returns null

        val cmd = PaymentCommand(partnerId = 1L, amount = BigDecimal("10000"), cardBin = "123456", cardLast4 = "4242", productName = "Test")

        assertFailsWith<IllegalStateException> {
            service.pay(cmd)
        }
    }
}
