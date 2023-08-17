package tywinlanni.github.com.plankaTelegram.db

import com.mongodb.client.model.IndexOptions
import org.litote.kmongo.coroutine.commitTransactionAndAwait
import org.litote.kmongo.reactivestreams.KMongo
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.eq
import tywinlanni.github.com.plankaTelegram.share.TelegramChatId

class MongoDb(
    connectionString: String,
    dbName: String,
): DAO {
    private val client by lazy { KMongo.createClient(connectionString).coroutine }
    private val db by lazy { client.getDatabase(dbName) }

    private val notificationCollection by lazy { db.getCollection<Notification>() }
    private val userPlankaCredentialsCollection by lazy { db.getCollection<UserPlankaCredentials>() }

    override suspend fun <T> doInTransaction(transaction: suspend DAO.() -> T): T =
        client.startSession().use { clientSession ->
            clientSession.startTransaction()
            val result = transaction()
            clientSession.commitTransactionAndAwait()
            result
        }

    override suspend fun getNotifications(): List<Notification> =
        notificationCollection.find().toList()

    override suspend fun addNotification(notification: Notification) {
        notificationCollection.insertOne(notification)
    }

    override suspend fun deleteNotification(telegramChatId: TelegramChatId) {
        notificationCollection.deleteOne(Notification::telegramChatId eq telegramChatId)
    }

    override suspend fun addOrUpdateUserCredentials(credentials: UserPlankaCredentials) {
        getCredentialsByTelegramId(credentials.telegramChatId)?.let {
            userPlankaCredentialsCollection.updateOne(UserPlankaCredentials::telegramChatId eq credentials.telegramChatId, credentials)
        } ?: userPlankaCredentialsCollection.insertOne(credentials)
    }

    override suspend fun getCredentialsByTelegramId(telegramChatId: TelegramChatId) =
        userPlankaCredentialsCollection.findOne(UserPlankaCredentials::telegramChatId eq telegramChatId)

    override suspend fun deletePlankaCredentials(telegramChatId: TelegramChatId) {
        userPlankaCredentialsCollection.deleteOne(UserPlankaCredentials::telegramChatId eq telegramChatId)
    }

    suspend fun createUniqueIndexIfNotExists() {
        notificationCollection.createIndex(key = "{ telegramChatId: 1 }", options = IndexOptions().unique(true))
        userPlankaCredentialsCollection.createIndex(key = "{ telegramChatId: 1 }", options = IndexOptions().unique(true))
    }
}
