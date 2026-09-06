package dev.gychoi.flashgate.infra.outbox

import dev.gychoi.flashgate.config.FlashgateProperties
import dev.gychoi.flashgate.domain.EventPublisher
import dev.gychoi.flashgate.domain.OutboxRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 아웃박스 릴레이(폴링). 한 트랜잭션 안에서 SKIP LOCKED 로 잠근 행을 Kafka 로 발행하고 published_at 을 찍는다.
 * 발행 후 커밋 전에 죽으면 같은 행이 다시 발행된다(at-least-once) → 컨슈머 멱등으로 흡수.
 */
@Component
class OutboxRelay(
    private val outbox: OutboxRepository,
    private val publisher: EventPublisher,
    private val props: FlashgateProperties,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${flashgate.outbox.poll-interval:100ms}")
    @Transactional
    fun tick() {
        val batch = outbox.pollUnpublished(props.outbox.batchSize)
        if (batch.isEmpty()) return
        for (ev in batch) publisher.publish(ev.type, ev.aggregateId, ev.payload)
        outbox.markPublished(batch.mapNotNull { it.id })
        meters.counter("flashgate.outbox.published").increment(batch.size.toDouble())
        log.debug("outbox relayed {} events", batch.size)
    }
}
