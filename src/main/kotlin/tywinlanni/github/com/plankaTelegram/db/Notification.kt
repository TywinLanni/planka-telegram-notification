package tywinlanni.github.com.plankaTelegram.db

import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import org.litote.kmongo.Id
import org.litote.kmongo.id.toId
import tywinlanni.github.com.plankaTelegram.wacher.Watcher

@Serializable
data class Notification(
    val id: Id<Notification> = ObjectId().toId(),
    val telegramChatId: Long,
    val plankaBoardId: Long,
    val types: List<Watcher.BoardAction>,
)
