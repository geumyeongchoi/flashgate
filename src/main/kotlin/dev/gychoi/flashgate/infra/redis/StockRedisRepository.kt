package dev.gychoi.flashgate.infra.redis

import dev.gychoi.flashgate.domain.IdempotencyStore
import dev.gychoi.flashgate.domain.Reservation
import dev.gychoi.flashgate.domain.ReservationStatus
import dev.gychoi.flashgate.domain.ReserveOutcome
import dev.gychoi.flashgate.domain.StockStore
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant

/**
 * 재고를 바꾸는 유일한 경로. HARD-GATE #2: Kotlin 코드에서 DECR/INCR 직접 호출 금지.
 * 스크립트는 SHA 캐시(EVALSHA)로 실행되어 왕복 1회 — 이 지점이 p99의 대부분을 결정한다.
 * Redis 장애 시: Retry(지수 백오프 3회) → CircuitBreaker(열리면 즉시 실패 → 503, 큐잉으로 지연 폭증 방지).
 */
@Repository
class StockRedisRepository(
    private val redis: StringRedisTemplate,
) : StockStore,
    IdempotencyStore {
    private val reserveScript =
        DefaultRedisScript<Long>().apply {
            setLocation(ClassPathResource("lua/reserve.lua"))
            resultType = Long::class.java
        }
    private val compensateScript =
        DefaultRedisScript<Long>().apply {
            setLocation(ClassPathResource("lua/compensate.lua"))
            resultType = Long::class.java
        }

    @Retry(name = "redis")
    @CircuitBreaker(name = "redis", fallbackMethod = "reserveFallback")
    override fun reserve(
        productId: Long,
        userId: String,
        orderId: String,
        ttlSeconds: Long,
    ): ReserveOutcome =
        when (redis.execute(reserveScript, keys(productId, orderId), userId, orderId, ttlSeconds.toString())) {
            1L -> ReserveOutcome.RESERVED
            0L -> ReserveOutcome.SOLD_OUT
            -1L -> ReserveOutcome.ALREADY_RESERVED
            else -> error("unexpected lua result")
        }

    override fun compensate(
        productId: Long,
        userId: String,
        orderId: String,
    ): Boolean = redis.execute(compensateScript, keys(productId, orderId), userId) == 1L

    override fun confirm(orderId: String): Boolean {
        val key = "reservation:$orderId"
        if (redis.opsForHash<String, String>().get(key, "status") != ReservationStatus.RESERVED.name) return false
        redis.opsForHash<String, String>().put(key, "status", ReservationStatus.CONFIRMED.name)
        redis.persist(key)
        return true
    }

    override fun remaining(productId: Long): Long = redis.opsForValue().get("stock:$productId")?.toLong() ?: 0

    override fun reservation(orderId: String): Reservation? {
        val h = redis.opsForHash<String, String>().entries("reservation:$orderId")
        if (h.isEmpty()) return null
        return Reservation(
            orderId = orderId,
            productId = h["productId"]?.toLong() ?: -1,
            userId = h["userId"] ?: "",
            idempotencyKey = h["idempotencyKey"] ?: "",
            status = ReservationStatus.valueOf(h["status"] ?: "RESERVED"),
            reservedAt = h["reservedAt"]?.toLongOrNull()?.let(Instant::ofEpochSecond) ?: Instant.EPOCH,
        )
    }

    override fun initStock(
        productId: Long,
        quantity: Long,
    ) {
        redis.opsForValue().set("stock:$productId", quantity.toString())
        redis.delete("reserved:$productId")
    }

    fun reservedCount(productId: Long): Long = redis.opsForSet().size("reserved:$productId") ?: 0

    // --- IdempotencyStore ---
    override fun putIfAbsent(
        key: String,
        response: String,
        ttlSeconds: Long,
    ): Boolean = redis.opsForValue().setIfAbsent("idem:$key", response, Duration.ofSeconds(ttlSeconds)) == true

    override fun get(key: String): String? = redis.opsForValue().get("idem:$key")

    @Suppress("unused", "UNUSED_PARAMETER")
    private fun reserveFallback(
        productId: Long,
        userId: String,
        orderId: String,
        ttlSeconds: Long,
        t: Throwable,
    ): ReserveOutcome = throw StockUnavailableException("redis unavailable (circuit open or retries exhausted)", t)

    private fun keys(
        productId: Long,
        orderId: String,
    ) = listOf("stock:$productId", "reserved:$productId", "reservation:$orderId")
}

class StockUnavailableException(
    msg: String,
    cause: Throwable?,
) : RuntimeException(msg, cause)
