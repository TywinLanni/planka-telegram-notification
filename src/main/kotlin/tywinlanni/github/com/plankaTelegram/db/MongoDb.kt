package tywinlanni.github.com.plankaTelegram.db

import com.mongodb.client.model.*
import com.mongodb.kotlin.client.coroutine.MongoClient
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import tywinlanni.github.com.plankaTelegram.share.TelegramChatId

class MongoDb(
    connectionString: String,
    dbName: String,
): DAO {
    private val client by lazy { MongoClient.create(connectionString) }
    private val db by lazy { client.getDatabase(dbName) }

    private val notificationCollection by lazy { db.getCollection<Notification>("notification") }
    private val userPlankaCredentialsCollection by lazy { db.getCollection<UserPlankaCredentials>("userPlankaCredentials") }

    override suspend fun <T> doInTransaction(transaction: suspend DAO.() -> T): T =
        client.startSession().use { clientSession ->
            clientSession.startTransaction()
            val result = transaction()
            clientSession.commitTransaction()
            result
        }

    override suspend fun getNotifications(): List<Notification> =
        notificationCollection.find().toList()

    override suspend fun addNotification(notification: Notification) {
        notificationCollection.insertOne(notification)
    }

    override suspend fun deleteNotification(telegramChatId: TelegramChatId) {
        notificationCollection.deleteOne(Filters.eq(Notification::telegramChatId.name, telegramChatId))
    }

    override suspend fun addOrUpdateUserCredentials(credentials: UserPlankaCredentials) {
        userPlankaCredentialsCollection.updateOne(
            Filters.eq(UserPlankaCredentials::telegramChatId.name, credentials.telegramChatId),
            Updates.combine(
                Updates.set(UserPlankaCredentials::plankaPassword.name, credentials.plankaPassword),
                Updates.set(UserPlankaCredentials::plankaLogin.name, credentials.plankaLogin),
            ),
            UpdateOptions().upsert(true),
        )
    }

    override suspend fun getCredentialsByTelegramId(telegramChatId: TelegramChatId) =
        userPlankaCredentialsCollection
            .find(Filters.eq(UserPlankaCredentials::telegramChatId.name, telegramChatId))
            .singleOrNull()

    override suspend fun deletePlankaCredentials(telegramChatId: TelegramChatId) {
        userPlankaCredentialsCollection.deleteOne(Filters.eq(UserPlankaCredentials::telegramChatId.name, telegramChatId))
    }

    suspend fun createUniqueIndexIfNotExists() {
        notificationCollection.createIndex(Indexes.ascending(Notification::telegramChatId.name), options = IndexOptions().unique(true))
        userPlankaCredentialsCollection.createIndex(Indexes.ascending(UserPlankaCredentials::telegramChatId.name), options = IndexOptions().unique(true))
    }
}
