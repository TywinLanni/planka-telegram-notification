package tywinlanni.github.com.plankaTelegram.planka

import kotlinx.serialization.Serializable
import tywinlanni.github.com.plankaTelegram.share.*

@Serializable
data class BoardResponse(
    val item: Board,
    val included: BoardIncluded,
)

@Serializable
data class BoardIncluded(
    val lists: List<PlankaList>,
    val cards: List<CardData>,
    val users: List<UserData>,
    val tasks: List<TaskData>,
)

@Serializable
data class PlankaList(
    val id: ListId,
    val boardId: BoardId,
    val name: String,
)

@Serializable
data class CardData(
    val name: String,
    val id: CardId,
    val boardId: BoardId,
    val listId: ListId,
    val creatorUserId: Long,
    val description: String?,
)

@Serializable
data class TaskData(
    val name: String,
    val id: TaskId,
    val cardId: CardId,
    val isCompleted: Boolean,
)

@Serializable
data class UserData(
    val name: String,
    val id: UserId,
)
