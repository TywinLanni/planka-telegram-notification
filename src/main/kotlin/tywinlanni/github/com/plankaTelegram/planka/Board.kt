package tywinlanni.github.com.plankaTelegram.planka

import kotlinx.serialization.Serializable

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
    val id: Long,
    val boardId: Long,
    val name: String,
)

@Serializable
data class CardData(
    val name: String,
    val id: Long,
    val boardId: Long,
    val listId: Long,
    val creatorUserId: Long,
    val description: Long,
)

@Serializable
data class TaskData(
    val name: String,
    val id: Long,
    val cardId: Long,
    val isCompleted: Boolean,
)

@Serializable
data class UserData(
    val name: String,
    val id: Long,
)
