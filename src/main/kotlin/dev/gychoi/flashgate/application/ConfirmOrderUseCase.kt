package dev.gychoi.flashgate.application

import com.fasterxml.jackson.databind.ObjectMapper
import dev.gychoi.flashgate.domain.EventPublisher
import dev.gychoi.flashgate.domain.EventTypes
import dev.gychoi.flashgate.domain.Order
import dev.gychoi.flashgate.domain.OrderRepository
import dev.gychoi.flashgate.domain.PaymentGateway
import dev.gychoi.flashgate.domain.PaymentResult
import dev.gychoi.flashgate.domain.ReservationStatus
import dev.gychoi.flashgate.domain.StockStore
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

data class OrderReservedEvent(
    val orderId: String,
    val productId: Long,
    val userId: String,
    val idempotencyKey: String,
)

/**
 * `order.reserved` 컨슈머 로직. at-least-once 전제: 같은 이벤트가 두 번 와도 orders.idempotency_key UNIQUE 로 무해.
 * 결제 승인 → orders INSERT(CONFIRMED) → Redis 예약 CONFIRMED → order.confirmed
 * 결제 거절 → compensate.lua(재고 복원) → orders INSERT(CANCELLED) → order.cancelled
 */
@Service
class ConfirmOrderUseCase(
    private val payment: PaymentGateway,
    private val orders: OrderRepository,
    private val stock: StockStore,
    private val publisher: EventPublisher,
    private val objectMapper: ObjectMapper,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun handle(ev: OrderReservedEvent) {
        if (orders.findById(ev.orderId) != null) {
            meters.counter("flashgate.confirm.duplicate").increment()
            return
        }
        when (val result = payment.charge(ev.orderId, ev.userId, ev.productId)) {
            is PaymentResult.Approved -> {
                val inserted =
                    orders.insertIfAbsent(
                        Order(
                            ev.orderId,
                            ev.productId,
                            ev.userId,
                            ReservationStatus.CONFIRMED,
                            ev.idempotencyKey,
                            confirmedAt = Instant.now(),
                        ),
                    )
                if (!inserted) return // 중복 소비
                stock.confirm(ev.orderId)
                publisher.publish(
                    EventTypes.ORDER_CONFIRMED,
                    ev.orderId,
                    objectMapper.writeValueAsString(
                        mapOf(
                            "orderId" to ev.orderId,
                            "approvalCode" to result.approvalCode,
                        ),
                    ),
                )
                meters.counter("flashgate.confirm", "result", "confirmed").increment()
            }
            is PaymentResult.Declined -> {
                stock.compensate(ev.productId, ev.userId, ev.orderId)
                orders.insertIfAbsent(
                    Order(ev.orderId, ev.productId, ev.userId, ReservationStatus.CANCELLED, ev.idempotencyKey, reason = result.reason),
                )
                publisher.publish(
                    EventTypes.ORDER_CANCELLED,
                    ev.orderId,
                    objectMapper.writeValueAsString(
                        mapOf(
                            "orderId" to ev.orderId,
                            "reason" to result.reason,
                        ),
                    ),
                )
                meters.counter("flashgate.confirm", "result", "cancelled").increment()
                log.info("order {} cancelled: {}", ev.orderId, result.reason)
            }
        }
    }
}
