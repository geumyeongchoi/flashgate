package dev.gychoi.flashgate.api

import dev.gychoi.flashgate.application.ReconcileUseCase
import dev.gychoi.flashgate.domain.Product
import dev.gychoi.flashgate.domain.ProductRepository
import dev.gychoi.flashgate.domain.StockStore
import dev.gychoi.flashgate.infra.redis.StockRedisRepository
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class StockRequest(
    @field:PositiveOrZero val quantity: Long,
    val name: String? = null,
)

@RestController
@RequestMapping("/api/admin/products")
@Validated
class AdminController(
    private val products: ProductRepository,
    private val stock: StockStore,
    private val redisStock: StockRedisRepository,
    private val reconcile: ReconcileUseCase,
) {
    /** 상품 등록 + 재고 초기화(Redis). k6 setup 이 호출한다. */
    @PostMapping("/{id}/stock")
    fun setStock(
        @PathVariable id: Long,
        @RequestBody @Validated req: StockRequest,
    ): Map<String, Any> {
        products.upsert(Product(id, req.name ?: "product-$id", req.quantity))
        stock.initStock(id, req.quantity)
        return mapOf("productId" to id, "stock" to req.quantity)
    }

    @GetMapping("/{id}/stock")
    fun getStock(
        @PathVariable id: Long,
    ): Map<String, Any?> =
        mapOf(
            "productId" to id,
            "remaining" to stock.remaining(id),
            "reserved" to redisStock.reservedCount(id),
            "initial" to products.findById(id)?.initialStock,
        )

    @GetMapping("/{id}/reconcile")
    fun reconcile(
        @PathVariable id: Long,
    ): ReconcileUseCase.Report = reconcile.reconcile(id, redisStock.reservedCount(id))
}
