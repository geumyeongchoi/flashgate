package dev.gychoi.flashgate.unit

import com.fasterxml.jackson.databind.ObjectMapper
import dev.gychoi.flashgate.application.ReserveOrderUseCase
import dev.gychoi.flashgate.application.ReserveResult
import dev.gychoi.flashgate.config.FlashgateProperties
import dev.gychoi.flashgate.domain.IdempotencyStore
import dev.gychoi.flashgate.domain.OutboxRepository
import dev.gychoi.flashgate.domain.ReserveOutcome
import dev.gychoi.flashgate.domain.StockStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class ReserveOrderUseCaseTest {
    private val stock = mockk<StockStore>(relaxed = true)
    private val idem = mockk<IdempotencyStore>(relaxed = true)
    private val outbox = mockk<OutboxRepository>(relaxed = true)
    private val useCase = ReserveOrderUseCase(stock, idem, outbox, FlashgateProperties(), ObjectMapper(), SimpleMeterRegistry())

    @Test
    fun `멱등키가 이미 있으면 Lua를 호출하지 않고 저장된 orderId를 재반환`() {
        every { idem.get("k1") } returns "order-1"

        val r = useCase.reserve(1, "u1", "k1")

        r.shouldBeInstanceOf<ReserveResult.Reserved>().orderId shouldBe "order-1"
        r.replayed shouldBe true
        verify(exactly = 0) { stock.reserve(any(), any(), any(), any()) }
    }

    @Test
    fun `예약 성공이면 outbox에 order_reserved 1건을 남기고 멱등키를 저장`() {
        every { idem.get(any()) } returns null
        every { stock.reserve(1, "u1", any(), any()) } returns ReserveOutcome.RESERVED
        every { idem.putIfAbsent(any(), any(), any()) } returns true

        val r = useCase.reserve(1, "u1", "k2")

        r.shouldBeInstanceOf<ReserveResult.Reserved>().replayed shouldBe false
        verify(exactly = 1) { outbox.append(match { it.type == "order.reserved" && it.payload.contains("\"userId\":\"u1\"") }) }
        verify(exactly = 1) { idem.putIfAbsent("k2", any(), any()) }
    }

    @Test
    fun `품절이면 outbox·멱등키를 건드리지 않는다`() {
        every { idem.get(any()) } returns null
        every { stock.reserve(any(), any(), any(), any()) } returns ReserveOutcome.SOLD_OUT

        useCase.reserve(1, "u9", "k3") shouldBe ReserveResult.SoldOut
        verify(exactly = 0) { outbox.append(any()) }
        verify(exactly = 0) { idem.putIfAbsent(any(), any(), any()) }
    }

    @Test
    fun `outbox INSERT 실패 시 Redis 예약을 보상하고 예외를 전파`() {
        every { idem.get(any()) } returns null
        every { stock.reserve(any(), any(), any(), any()) } returns ReserveOutcome.RESERVED
        every { outbox.append(any()) } throws IllegalStateException("db down")

        runCatching { useCase.reserve(1, "u1", "k4") }.isFailure shouldBe true
        verify(exactly = 1) { stock.compensate(1, "u1", any()) }
    }
}
