package tywinlanni.github.com.plankaTelegram.planka

data class PlankaData(
    val projects: Map<Long, Project>,
    val boards: Map<Long, Board>,
    val cards: Map<Long, CardData>,
    val users: Map<Long, UserData>,
    val lists: Map<Long, PlankaList>,
    val tasks: Map<Long, TaskData>,
)
