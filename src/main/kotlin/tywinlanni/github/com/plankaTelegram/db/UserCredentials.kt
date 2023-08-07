package tywinlanni.github.com.plankaTelegram.db

import kotlinx.serialization.Serializable

@Serializable
data class UserCredentials(
    val telegramId: Long,
    val plankaLogin: String,
    val plankaPassword: String,
)
