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
    val cards: List<Card>,
)

@Serializable
data class PlankaList(
    val id: Long,
    val boardId: Long,
    val name: String,
)

@Serializable
data class Card(
    val id: Long,
    val boardId: Long,
    val listId: Long,
    val name: String,
)
