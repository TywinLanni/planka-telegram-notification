package tywinlanni.github.com.plankaTelegram

import tywinlanni.github.com.plankaTelegram.bot.NotificationBot
import tywinlanni.github.com.plankaTelegram.db.DAO
import tywinlanni.github.com.plankaTelegram.db.MongoDb
import tywinlanni.github.com.plankaTelegram.planka.PlankaClient
import tywinlanni.github.com.plankaTelegram.wacher.Watcher

suspend fun main() {
    val plankaUsername = System.getenv("PLANKA_SERVICE_USERNAME")
    val plankaPassword = System.getenv("PLANKA_SERVICE_PASSWORD")
    val plankaConnectionString = System.getenv("PLANKA_CONNECTION_STRING")

    val mongoDbConnectionString = System.getenv("MONGO_CONNECTION_STRING") ?: "mongodb://localhost:27017"
    val databaseName = System.getenv("DATABASE_NAME") ?: "Planka"

    val telegramBotToken = System.getenv("TELEGRAM_BOT_TOKEN")

    val maybeDisabledNotificationListNames = System.getenv("DISABLED_PLANKA_LIST_NAMES")
        ?.split(",")

    val client = PlankaClient(
        plankaUrl = plankaConnectionString,
        plankaUsername = plankaUsername,
        plankaPassword = plankaPassword,
        maybeDisabledNotificationListNames = maybeDisabledNotificationListNames,
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
        plankaUrl = plankaConnectionString,
    ).apply {
        startPolling()
    }

    Watcher(
        serviceClient = client,
        dao = dao,
        notificationBot = notificationBot,
        plankaUrl = plankaConnectionString,
    ).start()
}
