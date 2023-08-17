package tywinlanni.github.com.plankaTelegram

import tywinlanni.github.com.plankaTelegram.bot.NotificationBot
import tywinlanni.github.com.plankaTelegram.db.DAO
import tywinlanni.github.com.plankaTelegram.db.MongoDb
import tywinlanni.github.com.plankaTelegram.planka.PlankaClient
import tywinlanni.github.com.plankaTelegram.wacher.Watcher

suspend fun main() {
    val plankaHost = System.getenv("PLANKA_HOST")
    val plankaPort = System.getenv("PLANKA_PORT")
    val plankaProtocol = System.getenv("PLANKA_PROTOCOL") ?: "http"
    val plankaUrl = System.getenv("PLANKA_URL")

    val plankaUsername = System.getenv("PLANKA_SERVICE_USERNAME")
    val plankaPassword = System.getenv("PLANKA_SERVICE_PASSWORD")

    val mongoDbConnectionString = System.getenv("MONGO_CONNECTION_STRING") ?: "mongodb://localhost:27017"
    val databaseName = System.getenv("DATABASE_NAME") ?: "Planka"

    val telegramBotToken = System.getenv("TELEGRAM_BOT_TOKEN")

    require(plankaUrl != null || (plankaHost != null && plankaPort != null)) {
        "Planka url ot host/port not found"
    }

    val client = PlankaClient(
        plankaUrl = plankaUrl ?: "$plankaProtocol://$plankaHost:$plankaPort",
        plankaUsername = plankaUsername,
        plankaPassword = plankaPassword,
    )

    val dao: DAO = MongoDb(
        connectionString = mongoDbConnectionString,
        dbName = databaseName
    ).apply {
        createUniqueIndexIfNotExists()
    }

    val notificationBot = NotificationBot(
        botToken = telegramBotToken,
        dao = dao,
    ).apply {
        startPolling()
    }

    Watcher(
        serviceClient = client,
        dao = dao,
        notificationBot = notificationBot,
        plankaUrl = plankaUrl ?: "$plankaProtocol://$plankaHost:$plankaPort",
    ).apply {
        watchJob.start()
        notificationJob.start()
    }
}
