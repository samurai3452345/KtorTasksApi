package ru.student.tasks

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.sql.SQLException
import java.util.UUID

class ApiException(val status: HttpStatusCode, val code: String, message: String) : RuntimeException(message)

fun Application.configureHttpPlugins() {
    install(CallId) {
        // Идентификатор создаёт сервер; произвольный заголовок клиента в журнал не попадает.
        generate { UUID.randomUUID().toString() }
        replyToHeader("X-Request-ID")
    }
    install(CallLogging) {
        level = Level.INFO
        logger = LoggerFactory.getLogger("http.access")
        mdc("requestId") { it.callId }
        format { call ->
            val path = call.request.path().replace(Regex("[\\r\\n\\t]"), "_").take(500)
            "method=${call.request.httpMethod.value} path=$path status=${call.response.status()?.value} " +
                "durationMs=${call.processingTimeMillis()} requestId=${call.callId}"
        }
    }
    install(StatusPages) {
        exception<ApiException> { call, cause -> call.respond(cause.status, ApiError(cause.code, cause.message!!)) }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "Проверьте JSON и типы полей запроса"))
        }
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respond(HttpStatusCode.UnsupportedMediaType, ApiError("unsupported_media_type", "Используйте Content-Type: application/json"))
        }
        exception<SQLException> { call, cause ->
            call.application.log.error("Database operation failed, requestId=${call.callId}", cause)
            call.respond(HttpStatusCode.ServiceUnavailable, ApiError("database_unavailable", "База данных временно недоступна"))
        }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            call.application.log.error("Unexpected request error, requestId=${call.callId}", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", "Внутренняя ошибка сервера"))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ApiError("not_found", "Маршрут не найден"))
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respond(status, ApiError("method_not_allowed", "Метод не поддерживается этим маршрутом"))
        }
    }
}
