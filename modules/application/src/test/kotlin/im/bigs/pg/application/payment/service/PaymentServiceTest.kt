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




    @Test
    @DisplayName("수수료 정책 - 비율만 적용 (고정 수수료 0원)")
    fun `수수료 정책 - 비율만 적용`() {
        val service = PaymentService(partnerRepo, feeRepo, paymentRepo, listOf(pgClient))
        every { partnerRepo.findById(1L) } returns Partner(1L, "TEST", "Test", true)
        every { feeRepo.findEffectivePolicy(1L, any()) } returns FeePolicy(
            id = 10L, partnerId = 1L, effectiveFrom = LocalDateTime.of(2020,1,1,0,0),
            percentage = BigDecimal("0.0250"), fixedFee = BigDecimal.ZERO
        )
        val savedSlot = slot<Payment>()
        every { paymentRepo.save(capture(savedSlot)) } answers { savedSlot.captured.copy(id = 99L) }

        val cmd = PaymentCommand(partnerId = 1L, amount = BigDecimal("10000"), cardBin = "123456", cardLast4 = "4242", productName = "Test")
        val res = service.pay(cmd)

        assertEquals(BigDecimal("250"), res.feeAmount)  // 10000 * 0.025 = 250
        assertEquals(BigDecimal("9750"), res.netAmount)
    }

    @Test
    @DisplayName("수수료 정책 - 고정 수수료만 적용 (비율 0%)")
    fun `수수료 정책 - 고정 수수료만 적용`() {
        val service = PaymentService(partnerRepo, feeRepo, paymentRepo, listOf(pgClient))
        every { partnerRepo.findById(1L) } returns Partner(1L, "TEST", "Test", true)
        every { feeRepo.findEffectivePolicy(1L, any()) } returns FeePolicy(
            id = 10L, partnerId = 1L, effectiveFrom = LocalDateTime.of(2020,1,1,0,0),
            percentage = BigDecimal.ZERO, fixedFee = BigDecimal("500")
        )
        val savedSlot = slot<Payment>()
        every { paymentRepo.save(capture(savedSlot)) } answers { savedSlot.captured.copy(id = 99L) }

        val cmd = PaymentCommand(partnerId = 1L, amount = BigDecimal("10000"), cardBin = "123456", cardLast4 = "4242", productName = "Test")
        val res = service.pay(cmd)

        assertEquals(BigDecimal("500"), res.feeAmount)
        assertEquals(BigDecimal("9500"), res.netAmount)
    }

    @Test
    @DisplayName("수수료 정책 - 비율 + 고정 수수료 혼합")
    fun `수수료 정책 - 비율과 고정 수수료 혼합`() {
        val service = PaymentService(partnerRepo, feeRepo, paymentRepo, listOf(pgClient))
        every { partnerRepo.findById(1L) } returns Partner(1L, "TEST", "Test", true)
        every { feeRepo.findEffectivePolicy(1L, any()) } returns FeePolicy(
            id = 10L, partnerId = 1L, effectiveFrom = LocalDateTime.of(2020,1,1,0,0),
            percentage = BigDecimal("0.0300"), fixedFee = BigDecimal("100")
        )
        val savedSlot = slot<Payment>()
        every { paymentRepo.save(capture(savedSlot)) } answers { savedSlot.captured.copy(id = 99L) }

        val cmd = PaymentCommand(partnerId = 1L, amount = BigDecimal("10000"), cardBin = "123456", cardLast4 = "4242", productName = "Test")
        val res = service.pay(cmd)

        assertEquals(BigDecimal("400"), res.feeAmount)  // (10000 * 0.03) + 100 = 400
        assertEquals(BigDecimal("9600"), res.netAmount)
    }

    @Test
    @DisplayName("수수료 반올림 검증 - HALF_UP 방식")
    fun `수수료 반올림 검증`() {
        val service = PaymentService(partnerRepo, feeRepo, paymentRepo, listOf(pgClient))
        every { partnerRepo.findById(1L) } returns Partner(1L, "TEST", "Test", true)
        every { feeRepo.findEffectivePolicy(1L, any()) } returns FeePolicy(
            id = 10L, partnerId = 1L, effectiveFrom = LocalDateTime.of(2020,1,1,0,0),
            percentage = BigDecimal("0.0333"), fixedFee = BigDecimal.ZERO
        )
        val savedSlot = slot<Payment>()
        every { paymentRepo.save(capture(savedSlot)) } answers { savedSlot.captured.copy(id = 99L) }

        val cmd = PaymentCommand(partnerId = 1L, amount = BigDecimal("10000"), cardBin = "123456", cardLast4 = "4242", productName = "Test")
        val res = service.pay(cmd)

        // 10000 * 0.0333 = 333.0 → HALF_UP = 333
        assertEquals(BigDecimal("333"), res.feeAmount)
        assertEquals(BigDecimal("9667"), res.netAmount)
    }



    @Test
    @DisplayName("제휴사별로 다른 수수료 정책 적용")
    fun `제휴사별 다른 수수료 정책 적용`() {
        val service = PaymentService(partnerRepo, feeRepo, paymentRepo, listOf(pgClient))

        // Partner 1: 2.5% + 100원
        every { partnerRepo.findById(1L) } returns Partner(1L, "PARTNER_A", "Partner A", true)
        every { feeRepo.findEffectivePolicy(1L, any()) } returns FeePolicy(
            id = 10L, partnerId = 1L, effectiveFrom = LocalDateTime.of(2020,1,1,0,0),
            percentage = BigDecimal("0.0250"), fixedFee = BigDecimal("100")
        )
        val savedSlot1 = slot<Payment>()
        every { paymentRepo.save(capture(savedSlot1)) } answers { savedSlot1.captured.copy(id = 1L) }

        val cmd1 = PaymentCommand(partnerId = 1L, amount = BigDecimal("10000"), cardBin = "123456", cardLast4 = "4242", productName = "Test")
        val res1 = service.pay(cmd1)

        assertEquals(BigDecimal("350"), res1.feeAmount)  // (10000 * 0.025) + 100 = 350
        assertEquals(BigDecimal("9650"), res1.netAmount)

        // Partner 2: 3.5% + 50원
        every { partnerRepo.findById(2L) } returns Partner(2L, "PARTNER_B", "Partner B", true)
        every { feeRepo.findEffectivePolicy(2L, any()) } returns FeePolicy(
            id = 20L, partnerId = 2L, effectiveFrom = LocalDateTime.of(2020,1,1,0,0),
            percentage = BigDecimal("0.0350"), fixedFee = BigDecimal("50")
        )
        val savedSlot2 = slot<Payment>()
        every { paymentRepo.save(capture(savedSlot2)) } answers { savedSlot2.captured.copy(id = 2L) }

        val cmd2 = PaymentCommand(partnerId = 2L, amount = BigDecimal("10000"), cardBin = "123456", cardLast4 = "4242", productName = "Test")
        val res2 = service.pay(cmd2)

        assertEquals(BigDecimal("400"), res2.feeAmount)  // (10000 * 0.035) + 50 = 400
        assertEquals(BigDecimal("9600"), res2.netAmount)
    }

    @Test
    @DisplayName("effective_from 기준으로 시점별 정책 적용")
    fun `시점별 수수료 정책 변경 적용`() {
        val service = PaymentService(partnerRepo, feeRepo, paymentRepo, listOf(pgClient))
        every { partnerRepo.findById(1L) } returns Partner(1L, "TEST", "Test", true)

        // 2024-01-01 이전 정책: 2% + 100원
        val oldPolicy = FeePolicy(
            id = 10L, partnerId = 1L, effectiveFrom = LocalDateTime.of(2020,1,1,0,0),
            percentage = BigDecimal("0.0200"), fixedFee = BigDecimal("100")
        )

        // 2024-01-01 이후 정책: 3% + 200원
        val newPolicy = FeePolicy(
            id = 11L, partnerId = 1L, effectiveFrom = LocalDateTime.of(2024,1,1,0,0),
            percentage = BigDecimal("0.0300"), fixedFee = BigDecimal("200")
        )

        // 승인 시각이 2023-12-31이면 구 정책 적용
        val pgClientOld = object : PgClientOutPort {
            override fun supports(partnerId: Long) = true
            override fun approve(request: PgApproveRequest) =
                PgApproveResult("APP-OLD", LocalDateTime.of(2023,12,31,23,59), PaymentStatus.APPROVED)
        }
        val serviceOld = PaymentService(partnerRepo, feeRepo, paymentRepo, listOf(pgClientOld))
        every { feeRepo.findEffectivePolicy(1L, LocalDateTime.of(2023,12,31,23,59)) } returns oldPolicy
        val savedSlot1 = slot<Payment>()
        every { paymentRepo.save(capture(savedSlot1)) } answers { savedSlot1.captured.copy(id = 1L) }

        val cmd1 = PaymentCommand(partnerId = 1L, amount = BigDecimal("10000"), cardBin = "123456", cardLast4 = "4242", productName = "Test")
        val res1 = serviceOld.pay(cmd1)

        assertEquals(BigDecimal("300"), res1.feeAmount)  // (10000 * 0.02) + 100 = 300
        assertEquals(BigDecimal("0.0200"), res1.appliedFeeRate)

        // 승인 시각이 2024-01-01이면 신 정책 적용
        val pgClientNew = object : PgClientOutPort {
            override fun supports(partnerId: Long) = true
            override fun approve(request: PgApproveRequest) =
                PgApproveResult("APP-NEW", LocalDateTime.of(2024,1,1,0,0), PaymentStatus.APPROVED)
        }
        val serviceNew = PaymentService(partnerRepo, feeRepo, paymentRepo, listOf(pgClientNew))
        every { feeRepo.findEffectivePolicy(1L, LocalDateTime.of(2024,1,1,0,0)) } returns newPolicy
        val savedSlot2 = slot<Payment>()
        every { paymentRepo.save(capture(savedSlot2)) } answers { savedSlot2.captured.copy(id = 2L) }

        val cmd2 = PaymentCommand(partnerId = 1L, amount = BigDecimal("10000"), cardBin = "123456", cardLast4 = "4242", productName = "Test")
        val res2 = serviceNew.pay(cmd2)

        assertEquals(BigDecimal("500"), res2.feeAmount)  // (10000 * 0.03) + 200 = 500
        assertEquals(BigDecimal("0.0300"), res2.appliedFeeRate)
    }

    @Test
    @DisplayName("승인 시각(approvedAt) 기준으로 정책 조회")
    fun `승인 시각 기준 정책 조회`() {
        val approvalTime = LocalDateTime.of(2024,6,15,10,30)
        val pgClientWithTime = object : PgClientOutPort {
            override fun supports(partnerId: Long) = true
            override fun approve(request: PgApproveRequest) =
                PgApproveResult("APP-TIME", approvalTime, PaymentStatus.APPROVED)
        }
        val service = PaymentService(partnerRepo, feeRepo, paymentRepo, listOf(pgClientWithTime))
        every { partnerRepo.findById(1L) } returns Partner(1L, "TEST", "Test", true)

        // 정책 조회 시 approvalTime이 사용되는지 검증
        val capturedTime = slot<LocalDateTime>()
        every { feeRepo.findEffectivePolicy(1L, capture(capturedTime)) } returns FeePolicy(
            id = 10L, partnerId = 1L, effectiveFrom = LocalDateTime.of(2024,1,1,0,0),
            percentage = BigDecimal("0.0300"), fixedFee = BigDecimal("100")
        )
        val savedSlot = slot<Payment>()
        every { paymentRepo.save(capture(savedSlot)) } answers { savedSlot.captured.copy(id = 99L) }

        val cmd = PaymentCommand(partnerId = 1L, amount = BigDecimal("10000"), cardBin = "123456", cardLast4 = "4242", productName = "Test")
        service.pay(cmd)

        // findEffectivePolicy가 approvalTime으로 호출되었는지 확인
        assertEquals(approvalTime, capturedTime.captured)
    }
}
