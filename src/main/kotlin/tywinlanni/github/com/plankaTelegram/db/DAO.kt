package tywinlanni.github.com.plankaTelegram.db

interface DAO {
    suspend fun getNotifications(): List<Notification>

    suspend fun addNotification(notification: Notification)

    suspend fun deleteNotification(notificationId: Long)
}