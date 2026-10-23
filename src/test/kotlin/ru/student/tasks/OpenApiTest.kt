package ru.student.tasks

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class OpenApiTest {
    private val specification = checkNotNull(javaClass.getResource("/openapi/openapi.json")).readText()

    @Test fun `OpenAPI validates with resolved schema references and documents all API operations`() {
        val parsed = OpenAPIV3Parser().readContents(specification, null, ParseOptions().apply { isResolve = true })
        assertNotNull(parsed.openAPI)
        assertTrue(parsed.messages.isNullOrEmpty(), parsed.messages.toString())
        assertEquals("3.0.3", parsed.openAPI.openapi)
        val expected = setOf(
            "GET /health", "POST /api/auth/register", "POST /api/auth/login", "GET /api/me",
            "GET /api/tasks", "POST /api/tasks", "GET /api/tasks/stats", "GET /api/tasks/{id}",
            "DELETE /api/tasks/{id}", "POST /api/tasks/{id}/complete"
        )
        val documented = parsed.openAPI.paths.flatMap { (path, item) -> item.readOperationsMap().keys.map { "$it $path" } }.toSet()
        assertTrue(documented.containsAll(expected), "Missing operations: ${expected - documented}")
        val operationIds = parsed.openAPI.paths.values.flatMap { item -> item.readOperations().map { it.operationId } }
        assertTrue(operationIds.all { !it.isNullOrBlank() })
        assertEquals(operationIds.size, operationIds.toSet().size)
        assertEquals("http", parsed.openAPI.components.securitySchemes["bearerAuth"]!!.type.toString())
        assertEquals("bearer", parsed.openAPI.components.securitySchemes["bearerAuth"]!!.scheme)
    }

    @Test fun `OpenAPI describes authentication validation pagination and bodyless deletion accurately`() {
        val api = Json.parseToJsonElement(specification).jsonObject
        val paths = api["paths"]!!.jsonObject
        assertEquals(0, paths["/api/auth/login"]!!.jsonObject["post"]!!.jsonObject["security"]!!.jsonArray.size)
        assertEquals(0, paths["/api/auth/register"]!!.jsonObject["post"]!!.jsonObject["security"]!!.jsonArray.size)
        val tasks = paths["/api/tasks"]!!.jsonObject
        assertTrue(tasks["get"]!!.jsonObject["security"]!!.toString().contains("bearerAuth"))
        val parameters = tasks["get"]!!.jsonObject["parameters"]!!.jsonArray
        assertEquals(setOf("q", "completed", "limit", "offset"), parameters.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet())
        val limit = parameters.single { it.jsonObject["name"]!!.jsonPrimitive.content == "limit" }.jsonObject["schema"]!!.jsonObject
        assertEquals(1, limit["minimum"]!!.jsonPrimitive.int)
        assertEquals(100, limit["maximum"]!!.jsonPrimitive.int)
        val deletion = paths["/api/tasks/{id}"]!!.jsonObject["delete"]!!.jsonObject["responses"]!!.jsonObject["204"]!!.jsonObject
        assertFalse(deletion.containsKey("content"))
        val responses = api["components"]!!.jsonObject["responses"]!!.jsonObject
        assertTrue(responses["Unauthorized"]!!.toString().contains("WWW-Authenticate"))
        assertTrue(responses["TaskNotFound"]!!.toString().contains("task_not_found"))
    }

    @Test fun `Swagger UI and both specification URLs load locally without authentication or CDN`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            configureHttpPlugins()
            routing { apiDocumentation() }
        }
        val swagger = client.get("/swagger")
        assertEquals(HttpStatusCode.OK, swagger.status)
        assertEquals(ContentType.Text.Html, swagger.contentType()?.withoutParameters())
        val html = swagger.bodyAsText()
        assertTrue(html.contains("SwaggerUIBundle"))
        assertTrue(html.contains("/swagger/openapi.json"))
        assertFalse(html.contains("unpkg.com"))
        assertFalse(html.contains("cdnjs"))
        for (url in listOf("/openapi.json", "/swagger/openapi.json")) {
            val response = client.get(url)
            assertEquals(HttpStatusCode.OK, response.status, url)
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            assertEquals(specification, response.bodyAsText())
        }
        for (asset in listOf("swagger-ui.css", "swagger-ui-bundle.js", "swagger-ui-standalone-preset.js", "favicon-32x32.png")) {
            val response = client.get("/swagger-assets/swagger-ui@5.31.0/$asset")
            assertEquals(HttpStatusCode.OK, response.status, asset)
            assertTrue(response.bodyAsText().isNotBlank())
        }
        assertEquals(HttpStatusCode.NotFound, client.get("/swagger-assets/swagger-ui@5.31.0/missing.js").status)
        assertEquals(HttpStatusCode.OK, client.get("/swagger?docExpansion=none").status)
        val unsafeOption = client.get("/swagger") { parameter("docExpansion", "';alert(1);//") }
        assertEquals(HttpStatusCode.BadRequest, unsafeOption.status)
        assertEquals("validation_error", Json.parseToJsonElement(unsafeOption.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
    }
}
