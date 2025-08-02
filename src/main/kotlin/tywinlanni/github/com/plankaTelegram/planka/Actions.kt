package tywinlanni.github.com.plankaTelegram.planka

import kotlinx.serialization.Serializable
import tywinlanni.github.com.plankaTelegram.share.ActionId
import tywinlanni.github.com.plankaTelegram.share.CardId
import tywinlanni.github.com.plankaTelegram.share.UserId

@Serializable
data class Actions(
    val items: List<Action>,
)

@Serializable
data class Action(
    val id: ActionId,
    val cardId: CardId,
    val userId: UserId,
    val type: String,
)
