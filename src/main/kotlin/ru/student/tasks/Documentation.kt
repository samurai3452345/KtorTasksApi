package ru.student.tasks

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.swagger.*

private val SwaggerOptions = createRouteScopedPlugin("SwaggerOptions") {
    onCall { call ->
        val expansion = call.request.queryParameters["docExpansion"]
        if (expansion != null && expansion !in setOf("none", "list", "full")) {
            throw ApiException(HttpStatusCode.BadRequest, "validation_error", "docExpansion: none, list или full")
        }
    }
}

fun Route.apiDocumentation() {
    val specification = checkNotNull(javaClass.getResource("/openapi/openapi.json")).readText()
    get("/openapi.json") { call.respondText(specification, ContentType.Application.Json) }
    // Ресурсы Swagger включены в JAR. Интернет нужен только при первой сборке Maven.
    val assets = mapOf(
        "swagger-ui.css" to ContentType.Text.CSS,
        "swagger-ui-bundle.js" to ContentType.Application.JavaScript,
        "swagger-ui-standalone-preset.js" to ContentType.Application.JavaScript,
        "favicon-32x32.png" to ContentType.Image.PNG
    )
    get("/swagger-assets/swagger-ui@5.31.0/{asset}") {
        val name = call.parameters["asset"]
        val contentType = assets[name]
        if (contentType == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            val bytes = checkNotNull(javaClass.getResourceAsStream("/META-INF/resources/webjars/swagger-ui/5.31.0/$name"))
                .use { it.readBytes() }
            call.respondBytes(bytes, contentType)
        }
    }
    route("/swagger") {
        install(SwaggerOptions)
        swaggerUI(path = "", swaggerFile = "openapi/openapi.json") {
            version = "5.31.0"
            packageLocation = "/swagger-assets/swagger-ui"
            faviconLocation = "/swagger-assets/swagger-ui@5.31.0/favicon-32x32.png"
            deepLinking = true
        }
    }
}
