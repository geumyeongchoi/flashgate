package dev.gychoi.flashgate.infra.payment

import dev.gychoi.flashgate.config.FlashgateProperties
import dev.gychoi.flashgate.domain.PaymentGateway
import dev.gychoi.flashgate.domain.PaymentResult
import org.springframework.stereotype.Component
import java.util.concurrent.ThreadLocalRandom

/** 모의 PG: 설정된 지연·실패율로 승인/거절. 실패율 100%로 두면 보상 흐름만 검증할 수 있다. */
@Component
class MockPaymentGateway(
    private val props: FlashgateProperties,
) : PaymentGateway {
    override fun charge(
        orderId: String,
        userId: String,
        productId: Long,
    ): PaymentResult {
        val p = props.payment
        val latency =
            if (p.maxLatencyMs >
                p.minLatencyMs
            ) {
                ThreadLocalRandom.current().nextLong(p.minLatencyMs, p.maxLatencyMs + 1)
            } else {
                p.minLatencyMs
            }
        if (latency > 0) Thread.sleep(latency)
        return if (ThreadLocalRandom.current().nextDouble() < p.failureRate) {
            PaymentResult.Declined("PG_DECLINED")
        } else {
            PaymentResult.Approved("APR-" + orderId.take(8).uppercase())
        }
    }
}
