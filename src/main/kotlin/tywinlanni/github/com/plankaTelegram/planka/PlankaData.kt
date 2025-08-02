package tywinlanni.github.com.plankaTelegram.planka

import tywinlanni.github.com.plankaTelegram.share.*

data class PlankaData(
    val project: Project,
    val board: Board,
    val cards: Map<CardId, CardData>,
    val users: Map<UserId, UserData>,
    val lists: Map<ListId, PlankaList>,
    val taskList: Map<ListId, TaskList>,
    val tasks: Map<TaskId, TaskData>,
    val actions: Map<ActionId, Action>,
    val disabledListsId: Set<ListId>,
    val movedBackCards: Set<CardId>,
    val movedBackTasks: Set<TaskId>,
)
