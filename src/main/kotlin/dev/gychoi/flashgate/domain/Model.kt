package dev.gychoi.flashgate.domain

import java.time.Instant
import java.util.UUID

enum class ReservationStatus { RESERVED, CONFIRMED, CANCELLED }

/** 핫패스 결과. 재고 변경은 오직 Lua 스크립트 안에서 일어난다(HARD-GATE #2). */
enum class ReserveOutcome { RESERVED, SOLD_OUT, ALREADY_RESERVED }

data class Product(
    val id: Long,
    val name: String,
    val initialStock: Long,
)

/** 예약 = 핫패스에서 Redis에 기록되는 임시 상태. 확정/취소는 비동기 컨슈머가 결정한다. */
data class Reservation(
    val orderId: String,
    val productId: Long,
    val userId: String,
    val idempotencyKey: String,
    val status: ReservationStatus,
    val reservedAt: Instant = Instant.now(),
) {
    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** 확정 주문 = MySQL 정본. */
data class Order(
    val id: String,
    val productId: Long,
    val userId: String,
    val status: ReservationStatus,
    val idempotencyKey: String,
    val reason: String? = null,
    val createdAt: Instant = Instant.now(),
    val confirmedAt: Instant? = null,
)

/** 아웃박스 이벤트. payload 는 JSON 문자열(직렬화는 어댑터 책임). */
data class OutboxEvent(
    val id: Long?,
    val aggregateId: String,
    val type: String,
    val payload: String,
)

object EventTypes {
    const val ORDER_RESERVED = "order.reserved"
    const val ORDER_CONFIRMED = "order.confirmed"
    const val ORDER_CANCELLED = "order.cancelled"
}

/** 결제 결과(모의 PG). */
sealed interface PaymentResult {
    data class Approved(
        val approvalCode: String,
    ) : PaymentResult

    data class Declined(
        val reason: String,
    ) : PaymentResult
}
