package tywinlanni.github.com.plankaTelegram.db

import kotlinx.serialization.Serializable
import tywinlanni.github.com.plankaTelegram.share.TelegramChatId
import tywinlanni.github.com.plankaTelegram.share.UserId
import tywinlanni.github.com.plankaTelegram.wacher.Watcher

@Serializable
data class Notification(
    val telegramChatId: TelegramChatId,
    val userId: UserId,
    val watchedActions: List<Watcher.BoardAction>,
)
