package ru.student.tasks

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class HttpPluginsTest {
    // Ошибки создаются только в тестовом сервере; в приложении таких маршрутов нет.
    private fun pluginTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            configureHttpPlugins()
            routing {
                get("/known") { call.respondText("ok") }
                post("/echo") { call.receiveText(); call.respondText("ok") }
                get("/database-failure") { throw SQLException("private JDBC connection details") }
                get("/unexpected-failure") { error("private implementation details") }
                get("/conflict") { throw ApiException(HttpStatusCode.Conflict, "conflict", "Конфликт данных") }
            }
        }
        block()
    }

    private suspend fun assertError(response: HttpResponse, status: HttpStatusCode, code: String) {
        assertEquals(status, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("code", "message"), body.keys)
        assertEquals(code, body["code"]!!.jsonPrimitive.content)
        assertTrue(body["message"]!!.jsonPrimitive.content.isNotBlank())
        assertNotNull(response.headers["X-Request-ID"])
        assertFalse(response.bodyAsText().contains("private"))
        assertFalse(response.bodyAsText().contains("Exception"))
    }

    @Test fun `StatusPages turns unknown routes into JSON 404`() = pluginTest {
        assertError(client.get("/missing"), HttpStatusCode.NotFound, "not_found")
    }

    @Test fun `StatusPages turns unsupported methods into JSON 405`() = pluginTest {
        assertError(client.put("/known"), HttpStatusCode.MethodNotAllowed, "method_not_allowed")
    }

    @Test fun `StatusPages preserves business error status and code`() = pluginTest {
        assertError(client.get("/conflict"), HttpStatusCode.Conflict, "conflict")
    }

    @Test fun `StatusPages hides unexpected exception details behind JSON 500`() = pluginTest {
        assertError(client.get("/unexpected-failure"), HttpStatusCode.InternalServerError, "internal_error")
    }

    @Test fun `StatusPages hides SQL details behind JSON 503`() = pluginTest {
        assertError(client.get("/database-failure"), HttpStatusCode.ServiceUnavailable, "database_unavailable")
    }

    @Test fun `request IDs are unique server-generated UUIDs on success and errors`() = pluginTest {
        val supplied = "client-supplied-id"
        val first = client.get("/known") { header("X-Request-ID", supplied) }.headers["X-Request-ID"]
        val second = client.get("/missing").headers["X-Request-ID"]
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first, UUID.fromString(first).toString())
        assertEquals(second, UUID.fromString(second).toString())
        assertNotEquals(first, second)
        assertNotEquals(supplied, first)
    }

    @Test fun `access logging records method status duration and ID without credentials`() {
        val logger = LoggerFactory.getLogger("http.access") as Logger
        val appender = ListAppender<ILoggingEvent>().apply {
            list = CopyOnWriteArrayList()
            start()
        }
        logger.addAppender(appender)
        try {
            pluginTest {
                val response = client.post("/echo?token=private-query-value") {
                    header(HttpHeaders.Authorization, "Bearer private-jwt-value")
                    contentType(ContentType.Application.Json)
                    setBody("""{"password":"private-password-value"}""")
                }
                assertEquals(HttpStatusCode.OK, response.status)
                val requestId = response.headers["X-Request-ID"]!!
                withTimeout(2000) {
                    while (appender.list.none { it.formattedMessage.contains(requestId) }) delay(10)
                }
                val event = appender.list.single { it.formattedMessage.contains(requestId) }
                val log = event.formattedMessage
                assertTrue(log.contains("method=POST path=/echo status=200"), log)
                assertTrue(Regex("durationMs=\\d+").containsMatchIn(log), log)
                assertEquals(requestId, event.mdcPropertyMap["requestId"])
                assertFalse(log.contains("private-"))
                assertFalse(log.contains("Authorization"))
                assertFalse(log.contains("password"))
                assertFalse(log.contains("?"))
            }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
