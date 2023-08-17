package tywinlanni.github.com.plankaTelegram.db

import kotlinx.serialization.Serializable
import tywinlanni.github.com.plankaTelegram.share.TelegramChatId

@Serializable
data class UserPlankaCredentials(
    val telegramChatId: TelegramChatId,
    val plankaLogin: String,
    val plankaPassword: String,
)
