package dev.gychoi.flashgate.api

import dev.gychoi.flashgate.infra.redis.StockUnavailableException
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

    @ExceptionHandler(StockUnavailableException::class)
    fun unavailable(e: StockUnavailableException): ResponseEntity<ProblemDetail> {
        val pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "재고 시스템에 일시적으로 접근할 수 없습니다. 잠시 후 다시 시도하세요")
        pd.type = URI.create("urn:flashgate:stock-unavailable")
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1").body(pd)
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
