package tywinlanni.github.com.plankaTelegram.wacher

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import tywinlanni.github.com.plankaTelegram.db.DAO
import tywinlanni.github.com.plankaTelegram.planka.*

class Watcher(
    private val serviceClient: PlankaClient,
    private val dao: DAO,
) {

    private val job = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + job)

    private val state by lazy { State() }
    private val diffChannel by lazy { Channel<Map<BoardAction, Set<Long>>>() }

    private val notificationJob = coroutineScope.launch {
        while (isActive) {
            val diff = diffChannel.receive()


        }
    }

    private val watchJob = coroutineScope.launch {
        while (isActive) {
            val projects = serviceClient.projects()

            val cards = mutableMapOf<Long, CardData>()
            val users = mutableMapOf<Long, UserData>()
            val lists = mutableMapOf<Long, PlankaList>()
            val tasks = mutableMapOf<Long, TaskData>()

            projects.included.boards
                .forEach { board ->
                    serviceClient.board(board.id)
                        .run {
                            cards.putAll(included.cards.associateBy { it.id })
                            users.putAll(included.users.associateBy { it.id })
                            lists.putAll(included.lists.associateBy { it.id })
                            tasks.putAll(included.tasks.associateBy { it.id })
                        }
                }

            state.setNewState(
                newState = PlankaData(
                    projects = projects.items.associateBy { it.id },
                    boards = projects.included.boards.associateBy { it.id },
                    cards = cards,
                    users = users,
                    lists = lists,
                    tasks = tasks,
                )
            )

            delay(10_000)
        }
    }

    inner class State {
        private var state: PlankaData? = null

        suspend fun setNewState(newState: PlankaData) {
            if (state == null) {
                state = newState
                return
            }
            findDiff(newState)

            state = newState
        }

        private suspend fun findDiff(newState: PlankaData) {
            val oldState = state
            require(oldState != null)

            val newDiff = BoardAction.entries
                .associateWith { mutableSetOf<Long>() }

            newDiff[BoardAction.ADD]?.addAll(
                newState.cards.values.map(CardData::id) - oldState.cards.values.map(CardData::id).toSet()
            )

            newDiff[BoardAction.DELETE]?.addAll(
                oldState.cards.values.map(CardData::id) - newState.cards.values.map(CardData::id).toSet()
            )

            newDiff[BoardAction.MOVE_TO_LIST]?.addAll(
                newState.cards.values.filter { newCardData ->
                    newCardData.listId != oldState.cards[newCardData.id]?.listId
                }.map(CardData::id)
            )

            newDiff[BoardAction.MOVE_TO_BOARD]?.addAll(
                newState.cards.values.filter { newCardData ->
                    newCardData.boardId != oldState.cards[newCardData.id]?.boardId
                }.map(CardData::id)
            )

            newDiff[BoardAction.UPDATE]?.addAll(
                newState.cards.values.filter { newCardData ->
                    newCardData != oldState.cards[newCardData.id]
                }.map(CardData::id)
                    .filter { updatedCard ->
                        updatedCard !in (newDiff[BoardAction.MOVE_TO_LIST] ?: emptySet()) &&
                                updatedCard !in (newDiff[BoardAction.MOVE_TO_BOARD] ?: emptySet())
                    }
            )

            // Check tasks diff
            newDiff[BoardAction.UPDATE]?.addAll(
                (newState.tasks.values.map(TaskData::id) - oldState.tasks.values.map(TaskData::id).toSet())
                    .mapNotNull { taskId ->
                        newState.tasks[taskId]?.cardId
                    }
            )

            newDiff[BoardAction.UPDATE]?.addAll(
                (oldState.tasks.values.map(TaskData::id) - newState.tasks.values.map(TaskData::id).toSet())
                    .mapNotNull { taskId ->
                        oldState.tasks[taskId]?.cardId
                    }
            )

            newDiff[BoardAction.UPDATE]?.addAll(
                newState.tasks.values.filter { newTaskData ->
                    newTaskData != oldState.tasks[newTaskData.id]
                }.mapNotNull(TaskData::cardId)
            )

            // Filter updated cards
            newDiff[BoardAction.UPDATE]?.removeIf { updatedCard ->
                updatedCard in (newDiff[BoardAction.ADD] ?: emptySet())
            }

            if (newDiff.values.flatten().isEmpty())
                return

            diffChannel.send(newDiff)
        }
    }

    enum class BoardAction {
        ADD,
        UPDATE,
        MOVE_TO_LIST,
        MOVE_TO_BOARD,
        DELETE
    }
}
