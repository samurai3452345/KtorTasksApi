package ru.student.tasks

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.sql.SQLException
import java.util.Locale

class ApiException(val status: HttpStatusCode, val code: String, message: String) : RuntimeException(message)

fun Application.module(settings: Settings, database: Database) {
    val users = UserRepository(database)
    val tasks = TaskRepository(database)
    val tokens = Tokens(settings)
    // Для неизвестного логина всё равно проверяем хеш: ответ и затраты времени одинаковы.
    val dummyHash = listOf("pbkdf2_sha256", "600000", "AAAAAAAAAAAAAAAAAAAAAA==",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").joinToString("$")

    install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
    install(StatusPages) {
        exception<ApiException> { call, cause -> call.respond(cause.status, ApiError(cause.code, cause.message!!)) }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "Проверьте JSON и типы полей запроса"))
        }
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respond(HttpStatusCode.UnsupportedMediaType, ApiError("unsupported_media_type", "Используйте Content-Type: application/json"))
        }
        exception<SQLException> { call, cause ->
            call.application.log.error("Database operation failed", cause)
            call.respond(HttpStatusCode.ServiceUnavailable, ApiError("database_unavailable", "База данных временно недоступна"))
        }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            call.application.log.error("Unexpected request error", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", "Внутренняя ошибка сервера"))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ApiError("not_found", "Маршрут не найден"))
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respond(status, ApiError("method_not_allowed", "Метод не поддерживается этим маршрутом"))
        }
    }
    install(Authentication) {
        jwt("jwt") {
            realm = "ktor-tasks-api"
            verifier(tokens.verifier)
            validate { credential ->
                val id = credential.payload.subject?.toLongOrNull()
                if (id != null && id > 0 && credential.payload.expiresAt != null && users.findById(id) != null)
                    JWTPrincipal(credential.payload) else null
            }
            challenge { _, _ ->
                call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"ktor-tasks-api\"")
                call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Требуется действующий Bearer JWT"))
            }
        }
    }

    routing {
        get("/") { call.respond(HealthResponse("Ktor Tasks API; see requests.http")) }
        get("/health") {
            database.query { connection -> connection.createStatement().use { it.execute("SELECT 1") } }
            call.respond(HealthResponse("ok"))
        }
        route("/api/auth") {
            post("/register") {
                val credentials = call.receive<Credentials>()
                val username = normalizeUsername(credentials.username)
                validatePassword(credentials.password)
                val user = users.create(username, Passwords.hash(credentials.password))
                    ?: throw ApiException(HttpStatusCode.Conflict, "username_taken", "Этот логин уже занят")
                call.respond(HttpStatusCode.Created, user)
            }
            post("/login") {
                val credentials = call.receive<Credentials>()
                val username = normalizeUsername(credentials.username)
                validatePassword(credentials.password)
                val user = users.find(username)
                val validPassword = Passwords.verify(credentials.password, user?.passwordHash ?: dummyHash)
                if (user == null || !validPassword)
                    throw ApiException(HttpStatusCode.Unauthorized, "invalid_credentials", "Неверный логин или пароль")
                call.respond(tokens.issue(user))
            }
        }
        authenticate("jwt") {
            get("/api/me") { call.respond(checkNotNull(users.findById(call.userId()))) }
            route("/api/tasks") {
                get {
                    val query = call.request.queryParameters
                    val completed = query["completed"]?.let {
                        it.toBooleanStrictOrNull() ?: badRequest("completed должен быть true или false")
                    }
                    val search = query["q"]?.trim() ?: ""
                    if (search.length > 200) badRequest("q должен содержать не больше 200 символов")
                    val limit = query["limit"]?.let { it.toIntOrNull() ?: badRequest("limit должен быть целым числом") } ?: 20
                    val offset = query["offset"]?.let { it.toIntOrNull() ?: badRequest("offset должен быть целым числом") } ?: 0
                    if (limit !in 1..100 || offset < 0) badRequest("limit: от 1 до 100; offset: не меньше 0")
                    call.respond(TaskPage(tasks.list(call.userId(), completed, search, limit, offset), limit, offset))
                }
                post {
                    val body = call.receive<CreateTaskRequest>()
                    val title = body.title.trim()
                    if (title.length !in 1..200) badRequest("title должен содержать от 1 до 200 символов")
                    if (body.description.length > 2000) badRequest("description должен содержать не больше 2000 символов")
                    val task = tasks.create(call.userId(), body.copy(title = title))
                    call.response.header(HttpHeaders.Location, "/api/tasks/${task.id}")
                    call.respond(HttpStatusCode.Created, task)
                }
                get("/{id}") {
                    call.respond(tasks.find(call.userId(), call.taskId()) ?: taskNotFound())
                }
                post("/{id}/complete") {
                    call.respond(tasks.complete(call.userId(), call.taskId()) ?: taskNotFound())
                }
                delete("/{id}") {
                    if (!tasks.delete(call.userId(), call.taskId())) taskNotFound()
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

private fun normalizeUsername(value: String): String {
    val username = value.trim().lowercase(Locale.ROOT)
    if (!username.matches(Regex("[a-z0-9_]{3,32}")))
        badRequest("Логин: 3–32 символа, латинские буквы, цифры и знак подчёркивания")
    return username
}

private fun validatePassword(password: String) {
    if (password.length !in 8..128) badRequest("Пароль должен содержать от 8 до 128 символов")
}

private fun ApplicationCall.userId(): Long = checkNotNull(principal<JWTPrincipal>()).payload.subject.toLong()

private fun ApplicationCall.taskId(): Long = parameters["id"]?.toLongOrNull()?.takeIf { it > 0 }
    ?: badRequest("id должен быть положительным целым числом")

private fun badRequest(message: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, "validation_error", message)

private fun taskNotFound(): Nothing = throw ApiException(HttpStatusCode.NotFound, "task_not_found", "Задача не найдена")
