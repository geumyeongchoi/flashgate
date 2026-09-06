package dev.gychoi.flashgate.infra.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.gychoi.flashgate.application.ConfirmOrderUseCase
import dev.gychoi.flashgate.application.OrderReservedEvent
import dev.gychoi.flashgate.domain.EventPublisher
import dev.gychoi.flashgate.domain.EventTypes
import org.apache.kafka.clients.admin.NewTopic
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
class KafkaEventPublisher(
    private val kafka: KafkaTemplate<String, String>,
) : EventPublisher {
    /** 동기 확인(acks=all). 아웃박스 트랜잭션 안에서 호출되므로 실패하면 롤백 → 다음 tick 재시도. */
    override fun publish(
        topic: String,
        key: String,
        payload: String,
    ) {
        kafka.send(topic, key, payload).get()
    }
}

@Component
class OrderReservedConsumer(
    private val confirm: ConfirmOrderUseCase,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = [EventTypes.ORDER_RESERVED], groupId = "\${spring.kafka.consumer.group-id:order-confirm}", concurrency = "3")
    fun onReserved(
        payload: String,
        ack: Acknowledgment,
    ) {
        val ev: OrderReservedEvent = objectMapper.readValue(payload)
        confirm.handle(ev)
        ack.acknowledge()
    }
}

@Configuration
class KafkaTopics {
    @Bean fun orderReserved(): NewTopic = NewTopic(EventTypes.ORDER_RESERVED, 6, 1)

    @Bean fun orderConfirmed(): NewTopic = NewTopic(EventTypes.ORDER_CONFIRMED, 6, 1)

    @Bean fun orderCancelled(): NewTopic = NewTopic(EventTypes.ORDER_CANCELLED, 6, 1)
}
