package dev.gychoi.flashgate.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "flashgate")
data class FlashgateProperties(
    val reservationTtl: Duration = Duration.ofMinutes(10),
    val idempotencyTtl: Duration = Duration.ofHours(24),
    val outbox: Outbox = Outbox(),
    val payment: Payment = Payment(),
) {
    data class Outbox(
        val pollInterval: Duration = Duration.ofMillis(100),
        val batchSize: Int = 200,
    )

    /** 모의 PG: 지연·실패율을 설정으로 조절해 보상 흐름을 검증한다. */
    data class Payment(
        val failureRate: Double = 0.05,
        val minLatencyMs: Long = 50,
        val maxLatencyMs: Long = 200,
    )
}
