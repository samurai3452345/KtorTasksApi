package ru.student.tasks

import java.sql.ResultSet

class UserRepository(private val database: Database) {
    suspend fun create(username: String, passwordHash: String): UserResponse? = database.query { connection ->
        connection.prepareStatement(
            "INSERT INTO app_users(username, password_hash) VALUES (?, ?) ON CONFLICT (username) DO NOTHING RETURNING id, username"
        ).use { statement ->
            statement.setString(1, username)
            statement.setString(2, passwordHash)
            statement.executeQuery().use { rows ->
                if (rows.next()) UserResponse(rows.getLong("id"), rows.getString("username")) else null
            }
        }
    }

    suspend fun find(username: String): StoredUser? = database.query { connection ->
        connection.prepareStatement("SELECT id, username, password_hash FROM app_users WHERE username = ?").use { statement ->
            statement.setString(1, username)
            statement.executeQuery().use { rows ->
                if (rows.next()) StoredUser(rows.getLong("id"), rows.getString("username"), rows.getString("password_hash")) else null
            }
        }
    }

    suspend fun findById(id: Long): UserResponse? = database.query { connection ->
        connection.prepareStatement("SELECT id, username FROM app_users WHERE id = ?").use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { rows ->
                if (rows.next()) UserResponse(rows.getLong("id"), rows.getString("username")) else null
            }
        }
    }
}

class TaskRepository(private val database: Database) {
    suspend fun stats(ownerId: Long): TaskStats = database.query { connection ->
        connection.prepareStatement("""
            SELECT COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE NOT completed) AS active,
                   COUNT(*) FILTER (WHERE completed) AS completed
            FROM tasks WHERE owner_id = ?
        """.trimIndent()).use { statement ->
            statement.setLong(1, ownerId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                TaskStats(rows.getInt("total"), rows.getInt("active"), rows.getInt("completed"))
            }
        }
    }

    suspend fun create(ownerId: Long, request: CreateTaskRequest): TaskResponse = database.query { connection ->
        connection.prepareStatement("INSERT INTO tasks(owner_id, title, description) VALUES (?, ?, ?) RETURNING *").use { statement ->
            statement.setLong(1, ownerId)
            statement.setString(2, request.title)
            statement.setString(3, request.description)
            statement.executeQuery().use { rows -> check(rows.next()); rows.task() }
        }
    }

    suspend fun list(ownerId: Long, completed: Boolean?, search: String, limit: Int, offset: Int): List<TaskResponse> =
        database.query { connection ->
            // Все значения передаются через placeholders, включая поиск и пагинацию.
            connection.prepareStatement("""
                SELECT * FROM tasks WHERE owner_id = ?
                AND (?::boolean IS NULL OR completed = ?::boolean)
                AND (strpos(lower(title), lower(?)) > 0 OR strpos(lower(description), lower(?)) > 0)
                ORDER BY id DESC LIMIT ? OFFSET ?
            """.trimIndent()).use { statement ->
                statement.setLong(1, ownerId)
                statement.setObject(2, completed)
                statement.setObject(3, completed)
                statement.setString(4, search)
                statement.setString(5, search)
                statement.setInt(6, limit)
                statement.setInt(7, offset)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.task()) } }
            }
        }

    suspend fun find(ownerId: Long, id: Long): TaskResponse? = database.query { connection ->
        connection.prepareStatement("SELECT * FROM tasks WHERE id = ? AND owner_id = ?").use { statement ->
            statement.setLong(1, id)
            statement.setLong(2, ownerId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.task() else null }
        }
    }

    suspend fun complete(ownerId: Long, id: Long): TaskResponse? = database.query { connection ->
        connection.prepareStatement("UPDATE tasks SET completed = TRUE WHERE id = ? AND owner_id = ? RETURNING *").use { statement ->
            statement.setLong(1, id)
            statement.setLong(2, ownerId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.task() else null }
        }
    }

    suspend fun delete(ownerId: Long, id: Long): Boolean = database.query { connection ->
        connection.prepareStatement("DELETE FROM tasks WHERE id = ? AND owner_id = ?").use { statement ->
            statement.setLong(1, id)
            statement.setLong(2, ownerId)
            statement.executeUpdate() == 1
        }
    }

    private fun ResultSet.task() = TaskResponse(
        id = getLong("id"), title = getString("title"), description = getString("description"),
        completed = getBoolean("completed"), createdAt = getTimestamp("created_at").toInstant().toString()
    )
}
