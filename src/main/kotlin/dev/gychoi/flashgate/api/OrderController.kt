package dev.gychoi.flashgate.api

import dev.gychoi.flashgate.application.ReserveOrderUseCase
import dev.gychoi.flashgate.application.ReserveResult
import dev.gychoi.flashgate.domain.OrderRepository
import dev.gychoi.flashgate.domain.ReservationStatus
import dev.gychoi.flashgate.domain.StockStore
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ReserveRequest(
    @field:Positive val productId: Long,
    @field:NotBlank @field:Size(max = 64) val userId: String,
)

data class ReserveResponse(
    val orderId: String,
    val status: String,
    val replayed: Boolean = false,
)

data class OrderStatusResponse(
    val orderId: String,
    val status: String,
    val productId: Long?,
    val userId: String?,
    val reason: String? = null,
)

@RestController
@RequestMapping("/api/orders")
@Validated
class OrderController(
    private val reserve: ReserveOrderUseCase,
    private val stock: StockStore,
    private val orders: OrderRepository,
) {
    /**
     * 핫패스. 202 RESERVED / 409 sold-out / 409 duplicate-user / 503 stock-unavailable(Redis 장애).
     * Idempotency-Key 헤더가 없으면 userId+productId 로 대체(같은 유저의 같은 상품 재시도는 항상 같은 응답).
     */
    @PostMapping
    fun reserve(
        @RequestBody @Validated req: ReserveRequest,
        @RequestHeader("Idempotency-Key", required = false) idemHeader: String?,
    ): ResponseEntity<ReserveResponse> {
        val key = idemHeader?.takeIf { it.isNotBlank() } ?: "${req.userId}:${req.productId}"
        return when (val r = reserve.reserve(req.productId, req.userId, key)) {
            is ReserveResult.Reserved -> ResponseEntity.accepted().body(ReserveResponse(r.orderId, "RESERVED", r.replayed))
            ReserveResult.SoldOut -> throw ProblemException(HttpStatus.CONFLICT, "sold-out", "재고가 소진되었습니다")
            ReserveResult.AlreadyReserved -> throw ProblemException(HttpStatus.CONFLICT, "duplicate-user", "이미 예약한 사용자입니다")
        }
    }

    /** Redis 예약(RESERVED/CONFIRMED) 우선, 없으면 MySQL 정본. */
    @GetMapping("/{orderId}")
    fun status(
        @PathVariable orderId: String,
    ): OrderStatusResponse {
        stock.reservation(orderId)?.let { return OrderStatusResponse(orderId, it.status.name, it.productId, it.userId) }
        orders.findById(orderId)?.let { return OrderStatusResponse(orderId, it.status.name, it.productId, it.userId, it.reason) }
        throw ProblemException(HttpStatus.NOT_FOUND, "order-not-found", "주문을 찾을 수 없습니다: $orderId")
    }

    @Suppress("unused")
    private fun statusName(s: ReservationStatus) = s.name
}
