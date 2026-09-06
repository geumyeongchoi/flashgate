package dev.gychoi.flashgate.domain

/** 재고·예약 저장소(Redis). 재고 증감은 구현체의 Lua 스크립트 안에서만 일어난다. */
interface StockStore {
    fun initStock(
        productId: Long,
        quantity: Long,
    )

    fun reserve(
        productId: Long,
        userId: String,
        orderId: String,
        ttlSeconds: Long,
    ): ReserveOutcome

    /** 결제 실패/취소 시 재고 복원. 이미 취소/확정된 예약이면 false. */
    fun compensate(
        productId: Long,
        userId: String,
        orderId: String,
    ): Boolean

    fun confirm(orderId: String): Boolean

    fun remaining(productId: Long): Long

    fun reservation(orderId: String): Reservation?
}

/** 멱등키 저장소 — 같은 키의 재요청에는 첫 응답을 그대로 돌려준다. */
interface IdempotencyStore {
    /** 키를 처음 등록하면 true. 이미 있으면 false 와 함께 저장된 응답을 [existing] 으로 돌려준다. */
    fun putIfAbsent(
        key: String,
        response: String,
        ttlSeconds: Long,
    ): Boolean

    fun get(key: String): String?
}

interface OutboxRepository {
    fun append(event: OutboxEvent)

    /** 미발행 이벤트를 잠금(SKIP LOCKED)하여 가져온다. 호출자는 트랜잭션 안에서 [markPublished] 까지 수행. */
    fun pollUnpublished(limit: Int): List<OutboxEvent>

    fun markPublished(ids: List<Long>)
}

interface OrderRepository {
    /** 멱등키 UNIQUE 위반이면 false (이미 적재됨). */
    fun insertIfAbsent(order: Order): Boolean

    fun findById(id: String): Order?

    fun countByProductAndStatus(
        productId: Long,
        status: ReservationStatus,
    ): Long
}

interface ProductRepository {
    fun upsert(product: Product)

    fun findById(id: Long): Product?
}

interface EventPublisher {
    fun publish(
        topic: String,
        key: String,
        payload: String,
    )
}

interface PaymentGateway {
    fun charge(
        orderId: String,
        userId: String,
        productId: Long,
    ): PaymentResult
}
