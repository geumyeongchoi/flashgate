package dev.gychoi.flashgate.infra.persistence

import dev.gychoi.flashgate.domain.Order
import dev.gychoi.flashgate.domain.OrderRepository
import dev.gychoi.flashgate.domain.OutboxEvent
import dev.gychoi.flashgate.domain.OutboxRepository
import dev.gychoi.flashgate.domain.Product
import dev.gychoi.flashgate.domain.ProductRepository
import dev.gychoi.flashgate.domain.ReservationStatus
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp

@Repository
class JdbcProductRepository(
    private val jdbc: JdbcClient,
) : ProductRepository {
    override fun upsert(product: Product) {
        jdbc
            .sql(
                """
                INSERT INTO products (id, name, initial_stock) VALUES (:id, :name, :stock)
                ON DUPLICATE KEY UPDATE name = VALUES(name), initial_stock = VALUES(initial_stock)
                """.trimIndent(),
            ).param("id", product.id)
            .param("name", product.name)
            .param("stock", product.initialStock)
            .update()
    }

    override fun findById(id: Long): Product? =
        jdbc
            .sql("SELECT id, name, initial_stock FROM products WHERE id = :id")
            .param("id", id)
            .query { rs, _ -> Product(rs.getLong("id"), rs.getString("name"), rs.getLong("initial_stock")) }
            .optional()
            .orElse(null)
}

@Repository
class JdbcOrderRepository(
    private val jdbc: JdbcClient,
) : OrderRepository {
    override fun insertIfAbsent(order: Order): Boolean =
        try {
            jdbc
                .sql(
                    """
                    INSERT INTO orders (id, product_id, user_id, status, idempotency_key, reason, created_at, confirmed_at)
                    VALUES (:id, :productId, :userId, :status, :idem, :reason, :createdAt, :confirmedAt)
                    """.trimIndent(),
                ).param("id", order.id)
                .param("productId", order.productId)
                .param("userId", order.userId)
                .param("status", order.status.name)
                .param("idem", order.idempotencyKey)
                .param("reason", order.reason)
                .param("createdAt", Timestamp.from(order.createdAt))
                .param("confirmedAt", order.confirmedAt?.let(Timestamp::from))
                .update() == 1
        } catch (e: DuplicateKeyException) {
            false
        }

    override fun findById(id: String): Order? =
        jdbc
            .sql("SELECT * FROM orders WHERE id = :id")
            .param("id", id)
            .query { rs, _ ->
                Order(
                    rs.getString("id"),
                    rs.getLong("product_id"),
                    rs.getString("user_id"),
                    ReservationStatus.valueOf(rs.getString("status")),
                    rs.getString("idempotency_key"),
                    rs.getString("reason"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("confirmed_at")?.toInstant(),
                )
            }.optional()
            .orElse(null)

    override fun countByProductAndStatus(
        productId: Long,
        status: ReservationStatus,
    ): Long =
        jdbc
            .sql("SELECT count(*) FROM orders WHERE product_id = :p AND status = :s")
            .param("p", productId)
            .param("s", status.name)
            .query(Long::class.java)
            .single()
}

/**
 * 트랜잭셔널 아웃박스. 핫패스는 append 1건만. 릴레이는 SELECT ... FOR UPDATE SKIP LOCKED 로 여러 인스턴스가 경합해도
 * 같은 행을 중복 발행하지 않는다(중복돼도 컨슈머가 멱등).
 */
@Repository
class JdbcOutboxRepository(
    private val jdbc: JdbcClient,
) : OutboxRepository {
    override fun append(event: OutboxEvent) {
        jdbc
            .sql("INSERT INTO outbox (aggregate_id, type, payload) VALUES (:agg, :type, CAST(:payload AS JSON))")
            .param("agg", event.aggregateId)
            .param("type", event.type)
            .param("payload", event.payload)
            .update()
    }

    /** 호출자(OutboxRelay.tick)가 트랜잭션을 열어야 SKIP LOCKED 잠금이 발행·마킹까지 유지된다. */
    override fun pollUnpublished(limit: Int): List<OutboxEvent> =
        jdbc
            .sql(
                "SELECT id, aggregate_id, type, payload FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
            ).param("limit", limit)
            .query { rs, _ -> OutboxEvent(rs.getLong("id"), rs.getString("aggregate_id"), rs.getString("type"), rs.getString("payload")) }
            .list()

    override fun markPublished(ids: List<Long>) {
        if (ids.isEmpty()) return
        jdbc.sql("UPDATE outbox SET published_at = CURRENT_TIMESTAMP(3) WHERE id IN (:ids)").param("ids", ids).update()
    }
}
