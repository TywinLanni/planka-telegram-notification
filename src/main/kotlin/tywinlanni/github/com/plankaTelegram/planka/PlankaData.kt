package tywinlanni.github.com.plankaTelegram.planka

import tywinlanni.github.com.plankaTelegram.share.*

data class PlankaData(
    val projects: Map<ProjectId, Project>,
    val boards: Map<BoardId, Board>,
    val cards: Map<CardId, CardData>,
    val users: Map<UserId, UserData>,
    val lists: Map<ListId, PlankaList>,
    val tasks: Map<TaskId, TaskData>,
    val actions: Map<ActionId, Action>,
)
