package ru.student.tasks

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.test.*

class ApiTest {
    private val secret = "test-only-secret-at-least-thirty-two-characters"

    // Каждый тест использует свою схему PostgreSQL. Пользовательские данные не удаляются.
    private fun apiTest(block: suspend ApplicationTestBuilder.(Database) -> Unit) {
        val baseUrl = System.getenv("TEST_DATABASE_URL") ?: "jdbc:postgresql://localhost:55434/ktor_tasks"
        val username = System.getenv("TEST_DATABASE_USER") ?: "tasks_app"
        val password = System.getenv("TEST_DATABASE_PASSWORD") ?: "tasks_local_password"
        val schema = "test_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(baseUrl, username, password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        val separator = if ('?' in baseUrl) '&' else '?'
        val settings = Settings("$baseUrl${separator}currentSchema=$schema", username, password, secret)
        try {
            Database(settings).use { database ->
                database.initialize()
                testApplication {
                    application { module(settings, database) }
                    block(database)
                }
            }
        } finally {
            DriverManager.getConnection(baseUrl, username, password).use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    private suspend fun HttpClient.register(username: String = "student") = post("/api/auth/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"username":"$username","password":"StrongPass123!"}""")
    }

    private suspend fun HttpClient.login(username: String = "student"): String {
        val response = post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"StrongPass123!"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.json()["token"]!!.jsonPrimitive.content
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpClient.create(token: String, title: String = "Learn Kotlin"): HttpResponse = post("/api/tasks") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody("""{"title":"$title","description":"Ktor and PostgreSQL"}""")
    }

    @Test fun `registration login and profile never expose password hash`() = apiTest { database ->
        val registration = client.register("Student")
        assertEquals(HttpStatusCode.Created, registration.status)
        assertEquals(setOf("id", "username"), registration.json().keys)
        assertEquals("student", registration.json()["username"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Conflict, client.register("STUDENT").status)
        val token = client.login()
        val profile = client.get("/api/me") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, profile.status)
        assertEquals(setOf("id", "username"), profile.json().keys)
        val stored = checkNotNull(UserRepository(database).find("student"))
        assertTrue(stored.passwordHash.startsWith("pbkdf2_sha256"))
        assertFalse(stored.passwordHash.contains("StrongPass123!"))
    }

    @Test fun `tasks support create read filter pagination completion and deletion`() = apiTest {
        client.register()
        val token = client.login()
        val created = client.create(token)
        assertEquals(HttpStatusCode.Created, created.status)
        val id = created.json()["id"]!!.jsonPrimitive.long
        assertEquals("/api/tasks/$id", created.headers[HttpHeaders.Location])
        assertFalse(created.json()["completed"]!!.jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.OK, client.get("/api/tasks/$id") { bearerAuth(token) }.status)
        client.create(token, "Another task")
        val complete = client.post("/api/tasks/$id/complete") { bearerAuth(token) }
        assertTrue(complete.json()["completed"]!!.jsonPrimitive.boolean)
        val filtered = client.get("/api/tasks?completed=true&q=kOtLiN&limit=1&offset=0") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, filtered.status)
        assertEquals(id, filtered.json()["items"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.long)
        val page = client.get("/api/tasks?limit=1&offset=1") { bearerAuth(token) }
        assertEquals(1, page.json()["items"]!!.jsonArray.size)
        val deleted = client.delete("/api/tasks/$id") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertEquals("", deleted.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/api/tasks/$id") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/tasks/$id") { bearerAuth(token) }.status)
    }

    @Test fun `users cannot read complete delete or list someone else's tasks`() = apiTest {
        client.register("alice")
        client.register("bob")
        val alice = client.login("alice")
        val bob = client.login("bob")
        val id = client.create(alice).json()["id"]!!.jsonPrimitive.long
        assertEquals(HttpStatusCode.NotFound, client.get("/api/tasks/$id") { bearerAuth(bob) }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/tasks/$id/complete") { bearerAuth(bob) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/tasks/$id") { bearerAuth(bob) }.status)
        assertTrue(client.get("/api/tasks") { bearerAuth(bob) }.json()["items"]!!.jsonArray.isEmpty())
        assertEquals(HttpStatusCode.OK, client.get("/api/tasks/$id") { bearerAuth(alice) }.status)
    }

    @Test fun `missing malformed expired and forged tokens are unauthorized`() = apiTest {
        client.register()
        for (token in listOf<String?>(null, "garbage", signedToken(expired = true), signedToken(secret = "a-different-signature-key-with-enough-length"))) {
            val result = client.get("/api/tasks") { if (token != null) bearerAuth(token) }
            assertEquals(HttpStatusCode.Unauthorized, result.status)
            assertEquals("unauthorized", result.json()["code"]!!.jsonPrimitive.content)
        }
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/tasks") { setBody("{}") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/tasks/1").status)
    }

    @Test fun `wrong password and unknown user have identical error responses`() = apiTest {
        client.register()
        val responses = listOf("student", "unknown").map { username ->
            client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"$username","password":"wrong-password"}""")
            }
        }
        responses.forEach { assertEquals(HttpStatusCode.Unauthorized, it.status) }
        assertEquals(responses[0].bodyAsText(), responses[1].bodyAsText())
    }

    @Test fun `invalid query and path parameters return 400`() = apiTest {
        client.register()
        val token = client.login()
        for (path in listOf("?completed=maybe", "?limit=0", "?limit=101", "?limit=no", "?offset=-1", "?offset=no", "/abc", "/0", "/-5", "/9223372036854775808")) {
            val response = client.get("/api/tasks$path") { bearerAuth(token) }
            assertEquals(HttpStatusCode.BadRequest, response.status, path)
            assertEquals("validation_error", response.json()["code"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `malformed missing fields and invalid bodies return useful JSON errors`() = apiTest {
        client.register()
        val token = client.login()
        for (body in listOf("{broken", "{}", """{"title":null}""", """{"title":"  "}""", """{"title":"${"a".repeat(201)}"}""", """{"title":"valid","description":"${"a".repeat(2001)}"}""")) {
            val response = client.post("/api/tasks") {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, body.take(80))
            assertTrue(response.json().containsKey("message"))
        }
        val unsupported = client.post("/api/tasks") { bearerAuth(token); contentType(ContentType.Text.Plain); setBody("hello") }
        assertEquals(HttpStatusCode.UnsupportedMediaType, unsupported.status)
    }

    @Test fun `invalid credentials are rejected at registration`() = apiTest {
        for (body in listOf("""{"username":"a","password":"StrongPass123!"}""", """{"username":"valid","password":"short"}""")) {
            assertEquals(HttpStatusCode.BadRequest, client.post("/api/auth/register") {
                contentType(ContentType.Application.Json); setBody(body)
            }.status)
        }
    }

    @Test fun `health checks database and unknown routes use JSON`() = apiTest {
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        val missing = client.get("/unknown")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("not_found", missing.json()["code"]!!.jsonPrimitive.content)
    }

    private fun signedToken(expired: Boolean = false, secret: String = this.secret): String = JWT.create()
        .withSubject("1").withIssuer("ktor-tasks-api").withAudience("ktor-tasks-client")
        .withExpiresAt(Date.from(Instant.now().plusSeconds(if (expired) -60 else 3600)))
        .sign(Algorithm.HMAC256(secret))
}
