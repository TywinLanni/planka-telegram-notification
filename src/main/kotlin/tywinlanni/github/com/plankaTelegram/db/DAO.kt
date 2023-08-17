package tywinlanni.github.com.plankaTelegram.db

import tywinlanni.github.com.plankaTelegram.share.TelegramChatId

interface DAO {
    suspend fun <T> doInTransaction(transaction: suspend DAO.() -> T): T

    suspend fun getNotifications(): List<Notification>
    suspend fun addNotification(notification: Notification)
    suspend fun deleteNotification(telegramChatId: TelegramChatId)

    suspend fun addOrUpdateUserCredentials(credentials: UserPlankaCredentials)
    suspend fun getCredentialsByTelegramId(telegramChatId: TelegramChatId): UserPlankaCredentials?
    suspend fun deletePlankaCredentials(telegramChatId: TelegramChatId)
}
