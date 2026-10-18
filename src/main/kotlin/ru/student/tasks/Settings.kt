package ru.student.tasks

import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64

data class Settings(
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val jwtSecret: String,
    val issuer: String = "ktor-tasks-api",
    val audience: String = "ktor-tasks-client",
    val port: Int = 8090
) {
    companion object {
        fun fromEnvironment(): Settings = Settings(
            databaseUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:55434/ktor_tasks",
            databaseUser = System.getenv("DATABASE_USER") ?: "tasks_app",
            databasePassword = System.getenv("DATABASE_PASSWORD") ?: "tasks_local_password",
            jwtSecret = localSecret(),
            port = (System.getenv("PORT") ?: "8090").toInt().also { require(it in 1..65535) }
        )

        private fun localSecret(): String {
            val configured = System.getenv("JWT_SECRET")
            if (configured != null) {
                require(configured.length >= 32) { "JWT_SECRET must contain at least 32 characters" }
                return configured
            }
            // Сохраняем случайный ключ только локально, чтобы токены переживали перезапуск.
            val path = Path.of(".local", "jwt-secret")
            Files.createDirectories(path.parent)
            if (!Files.exists(path)) {
                val bytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
                Files.writeString(path, Base64.getEncoder().encodeToString(bytes))
            }
            return Files.readString(path).trim().also {
                require(it.length >= 32) { "Invalid local JWT secret: $path" }
            }
        }
    }
}
