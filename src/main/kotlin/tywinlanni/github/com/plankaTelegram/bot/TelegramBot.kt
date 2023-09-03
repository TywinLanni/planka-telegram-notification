package tywinlanni.github.com.plankaTelegram.bot

import com.github.kotlintelegrambot.entities.ChatId

interface TelegramBot {
    suspend fun sendNotification(chatId: ChatId, text: String)
}
