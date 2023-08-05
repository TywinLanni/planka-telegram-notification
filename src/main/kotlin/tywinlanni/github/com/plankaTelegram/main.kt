package tywinlanni.github.com.plankaTelegram

import tywinlanni.github.com.plankaTelegram.db.DAO
import tywinlanni.github.com.plankaTelegram.db.MongoDb
import tywinlanni.github.com.plankaTelegram.planka.PlankaClient
import tywinlanni.github.com.plankaTelegram.wacher.Watcher

suspend fun main() {
    val plankaHost = System.getenv("PLANKA_HOST")
    val plankaPort = System.getenv("PLANKA_PORT")
    val plankaProtocol = System.getenv("PLANKA_PROTOCOL") ?: "http"

    val plankaUsername = System.getenv("PLANKA_USERNAME")
    val plankaPassword = System.getenv("PLANKA_PASSWORD")

    val mongoDbConnectionString = System.getenv("MONGO_CONNECTION_STRING") ?: "mongodb://localhost"
    val databaseName = System.getenv("DATABASE_NAME") ?: "Planka"

    val client = PlankaClient(
        plankaHost = plankaHost,
        plankaPort = plankaPort,
        plankaProtocol = plankaProtocol,
        plankaUsername = plankaUsername,
        plankaPassword = plankaPassword,
    )

    val dao: DAO = MongoDb(
        connectionString = mongoDbConnectionString,
        dbName = databaseName
    ).apply {
        createUniqueIndexIfNotExists()
    }

    val watcher = Watcher(client, dao)
}