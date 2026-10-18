package ru.student.tasks

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection

class Database(settings: Settings) : AutoCloseable {
    private val pool = HikariDataSource(HikariConfig().apply {
        jdbcUrl = settings.databaseUrl
        username = settings.databaseUser
        password = settings.databasePassword
        maximumPoolSize = 5
        connectionTimeout = 5000
    })

    fun initialize() {
        val sql = checkNotNull(javaClass.getResource("/db/schema.sql")).readText()
        pool.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute(sql) }
                connection.commit()
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        }
    }

    suspend fun <T> query(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        pool.connection.use(block)
    }

    override fun close() = pool.close()
}
