package dev.gychoi.flashgate.application

import dev.gychoi.flashgate.domain.OrderRepository
import dev.gychoi.flashgate.domain.ProductRepository
import dev.gychoi.flashgate.domain.ReservationStatus
import dev.gychoi.flashgate.domain.StockStore
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger

/**
 * 재고 대사: 초기재고 == Redis 잔여 + CONFIRMED 수 + 미확정 예약 수.
 * 미확정 예약 수는 Redis reserved set 크기 - CONFIRMED - CANCELLED 로 근사한다(취소 예약은 set 에서 제거됨).
 * 불일치면 게이지 flashgate_stock_mismatch = 1.
 */
@Service
class ReconcileUseCase(
    private val products: ProductRepository,
    private val orders: OrderRepository,
    private val stock: StockStore,
    meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mismatch = AtomicInteger(0)

    init {
        meters.gauge("flashgate.stock.mismatch", mismatch)
    }

    data class Report(
        val productId: Long,
        val initial: Long,
        val remaining: Long,
        val confirmed: Long,
        val pending: Long,
        val consistent: Boolean,
    )

    fun reconcile(
        productId: Long,
        reservedSetSize: Long,
    ): Report {
        val product = products.findById(productId) ?: error("unknown product $productId")
        val remaining = stock.remaining(productId)
        val confirmed = orders.countByProductAndStatus(productId, ReservationStatus.CONFIRMED)
        val pending = (reservedSetSize - confirmed).coerceAtLeast(0)
        val consistent = product.initialStock == remaining + confirmed + pending
        mismatch.set(if (consistent) 0 else 1)
        if (!consistent) {
            log.warn(
                "stock mismatch product={} initial={} remaining={} confirmed={} pending={}",
                productId,
                product.initialStock,
                remaining,
                confirmed,
                pending,
            )
        }
        return Report(productId, product.initialStock, remaining, confirmed, pending, consistent)
    }
}
