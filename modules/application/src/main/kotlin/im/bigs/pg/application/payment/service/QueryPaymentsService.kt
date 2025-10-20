package im.bigs.pg.application.payment.service

import im.bigs.pg.application.payment.port.`in`.*
import im.bigs.pg.application.payment.port.out.PaymentOutPort
import im.bigs.pg.application.payment.port.out.PaymentQuery
import im.bigs.pg.domain.payment.PaymentStatus
import im.bigs.pg.domain.payment.PaymentSummary
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Base64

/**
 * 결제 내역 조회 유스케이스 구현체.
 *
 * 대용량 결제 데이터를 효율적으로 조회하기 위한 서비스입니다.
 * 커서 기반 페이징으로 성능을 보장하고, 필터 조건 기준 통계를 함께 제공합니다.
 *
 * ## 주요 기능
 * - 커서 기반 페이징: offset 방식 대비 대용량 데이터에서 안정적 성능
 * - 복합 필터 지원: partnerId, status, from/to 기간 조건 조합 가능
 * - 통계 정합성: items와 summary가 동일 필터 적용하여 데이터 일관성 보장
 * - 안전한 커서: Base64URL 인코딩으로 내부 구조 노출 최소화
 */
@Service
class QueryPaymentsService(
    private val paymentOutPort: PaymentOutPort
) : QueryPaymentsUseCase {
    /**
     * 커서 기반 페이징으로 결제 내역을 조회하고 필터 조건 기준 전체 통계를 함께 반환합니다.
     *
     * ## 페이징 방식
     * - 커서 기반 페이징 사용 (offset 방식 대비 대용량 데이터 처리 성능 우수)
     * - (createdAt DESC, id DESC) 복합 정렬 기준
     * - 다음 페이지 존재 시 nextCursor 반환 (Base64URL 인코딩)
     *
     * ## 통계 집계
     * - summary는 필터 조건(partnerId, status, from/to)과 동일한 집합을 대상으로 집계
     * - 커서는 통계 계산에 영향을 주지 않음 (전체 집합 대상)
     * - items가 페이징된 일부라도, summary는 항상 필터 조건 전체를 집계
     *
     * @param filter 조회 조건 (partnerId, status, from/to, cursor, limit)
     * @return 페이징된 결제 목록, 전체 통계, 다음 페이지 커서
     */
    override fun query(filter: QueryFilter): QueryResult {
        // 1. 커서 디코딩
        val (cursorCreatedAt, cursorId) = decodeCursor(filter.cursor)

        // 2. PaymentQuery 생성
        val query = PaymentQuery(
            partnerId = filter.partnerId,
            status = filter.status?.let { PaymentStatus.valueOf(it) },
            from = filter.from,
            to = filter.to,
            limit = filter.limit,
            cursorCreatedAt = cursorCreatedAt,
            cursorId = cursorId
        )

        // 3. 페이지 조회
        val page = paymentOutPort.findBy(query)

        // 4. 다음 커서 인코딩
        val nextCursor = if (page.hasNext) {
            encodeCursor(page.nextCursorCreatedAt, page.nextCursorId)
        } else {
            null
        }

        // 5. 결과 반환 (summary는 다음 단계에서 구현)
        return QueryResult(
            items = page.items,
            summary = PaymentSummary(
                count = 0,
                totalAmount = java.math.BigDecimal.ZERO,
                totalNetAmount = java.math.BigDecimal.ZERO
            ),
            nextCursor = nextCursor,
            hasNext = page.hasNext
        )
    }

    /**
     * 다음 페이지 이동을 위한 커서를 생성합니다.
     *
     * (createdAt, id) 조합을 "epochMillis:id" 형식의 문자열로 만든 후
     * Base64URL 인코딩하여 클라이언트에 전달합니다.
     *
     * @param createdAt 마지막 레코드의 생성 시각
     * @param id 마지막 레코드의 ID
     * @return Base64URL 인코딩된 커서 문자열, null이면 null 반환
     */
    private fun encodeCursor(createdAt: java.time.LocalDateTime?, id: Long?): String? {
        if (createdAt == null || id == null) return null
        val instant = createdAt.toInstant(java.time.ZoneOffset.UTC)
        val raw = "${instant.toEpochMilli()}:$id"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())
    }

    /**
     * 클라이언트가 전달한 커서를 복원합니다.
     *
     * Base64URL 디코딩 후 "epochMillis:id" 형식을 파싱하여
     * (createdAt, id) 쌍으로 변환합니다.
     * 디코딩 실패 시 (null, null)을 반환하여 첫 페이지 조회로 처리합니다.
     *
     * @param cursor Base64URL 인코딩된 커서 문자열
     * @return (createdAt, id) 쌍, 디코딩 실패 시 (null, null)
     */
    private fun decodeCursor(cursor: String?): Pair<java.time.LocalDateTime?, Long?> {
        if (cursor.isNullOrBlank()) return null to null
        return try {
            val raw = String(Base64.getUrlDecoder().decode(cursor))
            val parts = raw.split(":")
            val ts = parts[0].toLong()
            val id = parts[1].toLong()
            val instant = Instant.ofEpochMilli(ts)
            java.time.LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC) to id
        } catch (e: Exception) {
            null to null
        }
    }
}
