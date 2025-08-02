package tywinlanni.github.com.plankaTelegram.bot

interface TelegramBot {
    suspend fun sendNotification(chatId: Long, text: String)
}
