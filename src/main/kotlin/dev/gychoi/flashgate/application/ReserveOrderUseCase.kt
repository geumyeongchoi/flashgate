package dev.gychoi.flashgate.application

import com.fasterxml.jackson.databind.ObjectMapper
import dev.gychoi.flashgate.config.FlashgateProperties
import dev.gychoi.flashgate.domain.EventTypes
import dev.gychoi.flashgate.domain.IdempotencyStore
import dev.gychoi.flashgate.domain.OutboxEvent
import dev.gychoi.flashgate.domain.OutboxRepository
import dev.gychoi.flashgate.domain.Reservation
import dev.gychoi.flashgate.domain.ReserveOutcome
import dev.gychoi.flashgate.domain.StockStore
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service

sealed interface ReserveResult {
    data class Reserved(
        val orderId: String,
        val replayed: Boolean = false,
    ) : ReserveResult

    data object SoldOut : ReserveResult

    data object AlreadyReserved : ReserveResult
}

/**
 * 핫패스. 순서: 멱등키 조회 → Lua 원자 예약 → outbox INSERT 1건 → 멱등 응답 저장.
 * DB 동기 호출은 outbox INSERT 하나(HARD-GATE #3). 트랜잭션 없음 — outbox INSERT 실패 시 Redis 예약을 보상한다.
 */
@Service
class ReserveOrderUseCase(
    private val stock: StockStore,
    private val idempotency: IdempotencyStore,
    private val outbox: OutboxRepository,
    private val props: FlashgateProperties,
    private val objectMapper: ObjectMapper,
    private val meters: MeterRegistry,
) {
    fun reserve(
        productId: Long,
        userId: String,
        idempotencyKey: String,
    ): ReserveResult {
        idempotency.get(idempotencyKey)?.let { return ReserveResult.Reserved(it, replayed = true) }

        val orderId = Reservation.newId()
        val outcome = stock.reserve(productId, userId, orderId, props.reservationTtl.seconds)
        meters.counter("flashgate.reserve", "outcome", outcome.name).increment()
        return when (outcome) {
            ReserveOutcome.SOLD_OUT -> ReserveResult.SoldOut
            ReserveOutcome.ALREADY_RESERVED -> ReserveResult.AlreadyReserved
            ReserveOutcome.RESERVED -> {
                try {
                    val payload =
                        objectMapper.writeValueAsString(
                            mapOf("orderId" to orderId, "productId" to productId, "userId" to userId, "idempotencyKey" to idempotencyKey),
                        )
                    outbox.append(OutboxEvent(null, orderId, EventTypes.ORDER_RESERVED, payload))
                } catch (e: Exception) {
                    stock.compensate(productId, userId, orderId) // outbox 실패 → 예약 되돌림(재고 복원)
                    throw e
                }
                // 같은 멱등키로 먼저 들어온 요청이 있으면(경합) 그 응답을 우선한다 — 이 예약은 보상
                if (!idempotency.putIfAbsent(idempotencyKey, orderId, props.idempotencyTtl.seconds)) {
                    stock.compensate(productId, userId, orderId)
                    return ReserveResult.Reserved(idempotency.get(idempotencyKey) ?: orderId, replayed = true)
                }
                ReserveResult.Reserved(orderId)
            }
        }
    }
}
