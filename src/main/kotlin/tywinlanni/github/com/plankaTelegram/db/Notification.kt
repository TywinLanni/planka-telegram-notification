package tywinlanni.github.com.plankaTelegram.db

import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import org.litote.kmongo.Id
import org.litote.kmongo.id.toId

@Serializable
data class Notification(
    val id: Id<Notification> = ObjectId().toId(),
    val telegramChatId: Long,
    val plankaListId: Long,
    val enterTask: Boolean,
    val leaveTask: Boolean,
)
