package dev.gychoi.flashgate.infra.strategy

import dev.gychoi.flashgate.domain.Reservation
import dev.gychoi.flashgate.domain.ReservationStatus
import dev.gychoi.flashgate.domain.ReserveOutcome
import dev.gychoi.flashgate.domain.StockStore
import dev.gychoi.flashgate.infra.redis.StockRedisRepository
import org.redisson.api.RedissonClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * 대안 A — DB 비관락: SELECT ... FOR UPDATE 로 재고 행을 잠근 뒤 차감. 정확하지만 락 대기가 트래픽에 비례한다.
 * flashgate.strategy=dblock 일 때만 활성(@Primary 로 Lua 구현을 대체). 나머지 메서드는 Redis 구현에 위임.
 */
@Repository
@Primary
@ConditionalOnProperty(name = ["flashgate.strategy"], havingValue = "dblock")
class DbLockStockStore(
    private val jdbc: JdbcClient,
    private val redis: StockRedisRepository,
) : StockStore by redis {
    override fun initStock(
        productId: Long,
        quantity: Long,
    ) {
        jdbc
            .sql("INSERT INTO product_stock (product_id, remaining) VALUES (:p, :q) ON DUPLICATE KEY UPDATE remaining = :q")
            .param("p", productId)
            .param("q", quantity)
            .update()
        jdbc.sql("DELETE FROM reservations_db WHERE product_id = :p").param("p", productId).update()
        redis.initStock(productId, quantity)
    }

    @Transactional
    override fun reserve(
        productId: Long,
        userId: String,
        orderId: String,
        ttlSeconds: Long,
    ): ReserveOutcome {
        val remaining =
            jdbc
                .sql("SELECT remaining FROM product_stock WHERE product_id = :p FOR UPDATE")
                .param("p", productId)
                .query(Long::class.java)
                .optional()
                .orElse(0L)
        if (remaining <= 0) return ReserveOutcome.SOLD_OUT
        return try {
            jdbc
                .sql("INSERT INTO reservations_db (order_id, product_id, user_id, status) VALUES (:o, :p, :u, 'RESERVED')")
                .param("o", orderId)
                .param("p", productId)
                .param("u", userId)
                .update()
            jdbc.sql("UPDATE product_stock SET remaining = remaining - 1 WHERE product_id = :p").param("p", productId).update()
            ReserveOutcome.RESERVED
        } catch (e: DuplicateKeyException) {
            ReserveOutcome.ALREADY_RESERVED
        }
    }

    @Transactional
    override fun compensate(
        productId: Long,
        userId: String,
        orderId: String,
    ): Boolean {
        val n =
            jdbc
                .sql(
                    "UPDATE reservations_db SET status = 'CANCELLED' WHERE order_id = :o AND status = 'RESERVED'",
                ).param("o", orderId)
                .update()
        if (n == 0) return false
        jdbc.sql("UPDATE product_stock SET remaining = remaining + 1 WHERE product_id = :p").param("p", productId).update()
        return true
    }

    override fun confirm(orderId: String): Boolean =
        jdbc
            .sql(
                "UPDATE reservations_db SET status = 'CONFIRMED' WHERE order_id = :o AND status = 'RESERVED'",
            ).param("o", orderId)
            .update() ==
            1

    override fun remaining(productId: Long): Long =
        jdbc
            .sql(
                "SELECT remaining FROM product_stock WHERE product_id = :p",
            ).param("p", productId)
            .query(Long::class.java)
            .optional()
            .orElse(0L)

    override fun reservation(orderId: String): Reservation? =
        jdbc
            .sql("SELECT * FROM reservations_db WHERE order_id = :o")
            .param("o", orderId)
            .query { rs, _ ->
                Reservation(
                    rs.getString("order_id"),
                    rs.getLong("product_id"),
                    rs.getString("user_id"),
                    "",
                    ReservationStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("created_at").toInstant(),
                )
            }.optional()
            .orElse(null)
}

/**
 * 대안 B — Redisson 분산락: 상품별 RLock 안에서 GET → 검사 → DECR. 정확하지만 락 획득 왕복 + 대기가 추가된다.
 * flashgate.strategy=redisson 일 때만 활성.
 */
@Repository
@Primary
@ConditionalOnProperty(name = ["flashgate.strategy"], havingValue = "redisson")
class RedissonLockStockStore(
    private val redisson: RedissonClient,
    private val redis: StockRedisRepository,
) : StockStore by redis {
    override fun reserve(
        productId: Long,
        userId: String,
        orderId: String,
        ttlSeconds: Long,
    ): ReserveOutcome {
        val lock = redisson.getLock("lock:stock:$productId")
        if (!lock.tryLock(50, 2000, TimeUnit.MILLISECONDS)) throw LockTimeoutException("lock:stock:$productId")
        try {
            val stockBucket = redisson.getBucket<String>("stock:$productId")
            val reserved = redisson.getSet<String>("reserved:$productId")
            if (reserved.contains(userId)) return ReserveOutcome.ALREADY_RESERVED
            val remaining = stockBucket.get()?.toLong() ?: 0
            if (remaining <= 0) return ReserveOutcome.SOLD_OUT
            stockBucket.set((remaining - 1).toString())
            reserved.add(userId)
            val h = redisson.getMap<String, String>("reservation:$orderId")
            h.putAll(
                mapOf(
                    "userId" to userId,
                    "productId" to productId.toString(),
                    "status" to "RESERVED",
                    "reservedAt" to (System.currentTimeMillis() / 1000).toString(),
                ),
            )
            h.expire(Duration.ofSeconds(ttlSeconds))
            return ReserveOutcome.RESERVED
        } finally {
            if (lock.isHeldByCurrentThread) lock.unlock()
        }
    }
}

class LockTimeoutException(
    key: String,
) : RuntimeException("lock timeout: $key")
