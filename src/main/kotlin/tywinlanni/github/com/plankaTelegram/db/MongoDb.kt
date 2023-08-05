package tywinlanni.github.com.plankaTelegram.db

import com.mongodb.client.model.IndexOptions
import org.litote.kmongo.reactivestreams.KMongo
import org.litote.kmongo.coroutine.coroutine

class MongoDb(
    connectionString: String,
    dbName: String,
): DAO {
    private val client by lazy { KMongo.createClient(connectionString).coroutine }
    private val db by lazy { client.getDatabase(dbName) }

    private val notificationCollection by lazy { db.getCollection<Notification>() }

    override suspend fun getNotifications(): List<Notification> =
        notificationCollection.find().toList()

    override suspend fun addNotification(notification: Notification) {
        TODO("Not yet implemented")
    }

    override suspend fun deleteNotification(notificationId: Long) {
        TODO("Not yet implemented")
    }

    suspend fun createUniqueIndexIfNotExists() {
        notificationCollection.createIndex(key = "{ telegramChatId: 1, plankaListId: 1 }", options = IndexOptions().unique(true))
    }
}
