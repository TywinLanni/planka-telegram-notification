package tywinlanni.github.com.plankaTelegram.planka

import kotlinx.serialization.Serializable

@Serializable
data class UsersData(
    val items: List<UserData>,
)
