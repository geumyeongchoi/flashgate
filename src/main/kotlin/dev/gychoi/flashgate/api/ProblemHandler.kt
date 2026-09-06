package dev.gychoi.flashgate.api

import dev.gychoi.flashgate.infra.redis.StockUnavailableException
import dev.gychoi.flashgate.infra.strategy.LockTimeoutException
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

class ProblemException(
    val status: HttpStatus,
    val type: String,
    message: String,
) : RuntimeException(message)

/** RFC 9457 application/problem+json */
@RestControllerAdvice
class ProblemHandler {
    @ExceptionHandler(ProblemException::class)
    fun problem(e: ProblemException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(e.status, e.message).apply { type = URI.create("urn:flashgate:${e.type}") }

    @ExceptionHandler(LockTimeoutException::class)
    fun lockTimeout(e: LockTimeoutException): ResponseEntity<ProblemDetail> =
        unavailable(
            StockUnavailableException(e.message ?: "lock timeout", e),
        )

    @ExceptionHandler(StockUnavailableException::class)
    fun unavailable(e: StockUnavailableException): ResponseEntity<ProblemDetail> {
        val pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "재고 시스템에 일시적으로 접근할 수 없습니다. 잠시 후 다시 시도하세요")
        pd.type = URI.create("urn:flashgate:stock-unavailable")
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1").body(pd)
    }

    /** 멱등키 조회 등 Retry 경로 밖의 Redis 접근이 failover 중 실패하면 역시 503(빠른 실패)로. 그 외 DB 오류는 500. */
    @ExceptionHandler(DataAccessException::class)
    fun dataAccess(e: DataAccessException): ResponseEntity<ProblemDetail> {
        val transient =
            e is QueryTimeoutException || e is DataAccessResourceFailureException ||
                e.javaClass.name.startsWith("org.springframework.data.redis")
        if (transient) return unavailable(StockUnavailableException("redis transient failure: ${e.javaClass.simpleName}", e))
        val pd =
            ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "내부 오류").apply {
                type =
                    URI.create("urn:flashgate:internal")
            }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(pd)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(e: MethodArgumentNotValidException): ProblemDetail =
        ProblemDetail
            .forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                e.bindingResult.fieldErrors.joinToString("; ") {
                    "${it.field}: ${it.defaultMessage}"
                },
            ).apply { type = URI.create("urn:flashgate:validation") }
}
