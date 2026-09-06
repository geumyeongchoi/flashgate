package dev.gychoi.flashgate.integration

import com.redis.testcontainers.RedisContainer
import io.kotest.matchers.shouldBe
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ReserveFlowIntegrationTest {
    companion object {
        @Container @ServiceConnection @JvmStatic
        val redis = RedisContainer(DockerImageName.parse("redis:7.4"))

        @Container @ServiceConnection @JvmStatic
        val mysql =
            MySQLContainer(
                DockerImageName.parse("mysql:8.4"),
            ).withDatabaseName("flashgate").withUsername("flashgate").withPassword("flashgate")

        @Container @ServiceConnection @JvmStatic
        val kafka = KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"))
    }

    @Autowired lateinit var rest: TestRestTemplate

    @Autowired lateinit var jdbc: JdbcTemplate

    private val json = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }

    private fun setStock(
        productId: Long,
        qty: Long,
    ) {
        rest
            .postForEntity(
                "/api/admin/products/$productId/stock",
                HttpEntity("""{"quantity":$qty}""", json),
                Map::class.java,
            ).statusCode shouldBe
            HttpStatus.OK
    }

    private fun reserve(
        productId: Long,
        userId: String,
        idem: String? = null,
    ) = rest.postForEntity(
        "/api/orders",
        HttpEntity(
            """{"productId":$productId,"userId":"$userId"}""",
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                idem?.let { set("Idempotency-Key", it) }
            },
        ),
        Map::class.java,
    )

    @Test
    fun `동시 1000 요청 · 재고 100 → 정확히 100건 202, 초과판매 0, 전부 CONFIRMED 적재`() {
        val productId = 100L
        setStock(productId, 100)

        val ok = AtomicInteger()
        val soldOut = AtomicInteger()
        val other = AtomicInteger()
        val start = CountDownLatch(1)
        val done = CountDownLatch(1000)
        val pool = Executors.newVirtualThreadPerTaskExecutor()
        repeat(1000) { i ->
            pool.submit {
                start.await()
                try {
                    val res = reserve(productId, "user-$i")
                    when (res.statusCode.value()) {
                        202 -> ok.incrementAndGet()
                        409 -> soldOut.incrementAndGet()
                        else -> other.incrementAndGet()
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        done.await()

        ok.get() shouldBe 100
        soldOut.get() shouldBe 900
        other.get() shouldBe 0

        val stock = rest.getForObject("/api/admin/products/$productId/stock", Map::class.java)!!
        (stock["remaining"] as Number).toLong() shouldBe 0L
        (stock["reserved"] as Number).toLong() shouldBe 100L

        // 아웃박스 → Kafka → 컨슈머 → MySQL CONFIRMED 100건
        await.atMost(Duration.ofSeconds(60)).untilAsserted {
            jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE product_id = ? AND status = 'CONFIRMED'",
                Long::class.java,
                productId,
            ) shouldBe
                100L
        }
        jdbc.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL", Long::class.java) shouldBe 0L

        val report = rest.getForObject("/api/admin/products/$productId/reconcile", Map::class.java)!!
        report["consistent"] shouldBe true
    }

    @Test
    fun `같은 Idempotency-Key 재요청은 같은 orderId 를 재반환하고 재고를 다시 차감하지 않는다`() {
        val productId = 200L
        setStock(productId, 5)
        val first = reserve(productId, "idem-user", "key-A")
        val second = reserve(productId, "idem-user", "key-A")

        first.statusCode shouldBe HttpStatus.ACCEPTED
        second.statusCode shouldBe HttpStatus.ACCEPTED
        second.body!!["orderId"] shouldBe first.body!!["orderId"]
        second.body!!["replayed"] shouldBe true
        (rest.getForObject("/api/admin/products/$productId/stock", Map::class.java)!!["remaining"] as Number).toLong() shouldBe 4L
    }

    @Test
    fun `같은 유저의 두 번째 예약(다른 멱등키)은 409 duplicate-user`() {
        val productId = 300L
        setStock(productId, 5)
        reserve(productId, "dup-user", "k1").statusCode shouldBe HttpStatus.ACCEPTED
        val res = reserve(productId, "dup-user", "k2")
        res.statusCode shouldBe HttpStatus.CONFLICT
        res.body!!["type"].toString() shouldBe "urn:flashgate:duplicate-user"
    }

    @Test
    fun `품절이면 409 sold-out`() {
        val productId = 400L
        setStock(productId, 0)
        val res = reserve(productId, "any", "k9")
        res.statusCode shouldBe HttpStatus.CONFLICT
        res.body!!["type"].toString() shouldBe "urn:flashgate:sold-out"
    }
}
