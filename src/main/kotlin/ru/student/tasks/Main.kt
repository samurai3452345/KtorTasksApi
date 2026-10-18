package ru.student.tasks

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val settings = Settings.fromEnvironment()
    val database = Database(settings)
    try {
        database.initialize()
        embeddedServer(Netty, host = "127.0.0.1", port = settings.port) {
            module(settings, database)
        }.start(wait = true)
    } finally {
        database.close()
    }
}
