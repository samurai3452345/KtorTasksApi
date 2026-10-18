package ru.student.tasks

import kotlinx.serialization.Serializable

@Serializable
data class Credentials(val username: String, val password: String)

@Serializable
data class UserResponse(val id: Long, val username: String)

@Serializable
data class TokenResponse(val token: String, val expiresIn: Long = 3600, val tokenType: String = "Bearer")

@Serializable
data class CreateTaskRequest(val title: String, val description: String = "")

@Serializable
data class TaskResponse(
    val id: Long,
    val title: String,
    val description: String,
    val completed: Boolean,
    val createdAt: String
)

@Serializable
data class TaskPage(val items: List<TaskResponse>, val limit: Int, val offset: Int)

@Serializable
data class TaskStats(val total: Int, val active: Int, val completed: Int)

@Serializable
data class ApiError(val code: String, val message: String)

@Serializable
data class HealthResponse(val status: String)

data class StoredUser(val id: Long, val username: String, val passwordHash: String)
