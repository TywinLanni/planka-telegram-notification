package tywinlanni.github.com.plankaTelegram.wacher

import com.github.kotlintelegrambot.entities.ChatId
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import tywinlanni.github.com.plankaTelegram.bot.TelegramBot
import tywinlanni.github.com.plankaTelegram.db.DAO
import tywinlanni.github.com.plankaTelegram.db.Notification
import tywinlanni.github.com.plankaTelegram.planka.*
import tywinlanni.github.com.plankaTelegram.share.BoardId
import tywinlanni.github.com.plankaTelegram.share.CardId
import tywinlanni.github.com.plankaTelegram.share.TelegramChatId
import java.util.concurrent.ConcurrentHashMap

private val logger = LoggerFactory.getLogger(Watcher::class.java)

private const val NOTIFICATION_CACHE_UPDATE_DELAY = 900_000L
private const val PLANKA_STATE_SCAN_DELAY = 10_000L
private const val ANTI_SPAM_DELAY = 60_000L

private const val MAGIC_COMMENT_CARD_TYPE = "commentCard"

class Watcher(
    private val serviceClient: PlankaClient,
    private val dao: DAO,
    private val notificationBot: TelegramBot,
    private val plankaUrl: String,
) {
    private val job = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + job)

    private val state by lazy { State() }
    private val diffChannel by lazy { Channel<Pair<PlankaData, StateDiff>>() }

    private val notificationBoardsCache by lazy { mutableMapOf<TelegramChatId, Set<BoardId>>() }
    private val notificationBoardsCacheMutex by lazy { Mutex() }

    private val spamProtectedCards by lazy { mutableSetOf<CardId>() }
    private val spamProtectedCardsMutex by lazy { Mutex() }

    fun start() {
        updateCacheJob.start()
        watchJob.start()
        notificationJob.start()
    }

    private val updateCacheJob = coroutineScope.launch(start = CoroutineStart.LAZY) {
        while (isActive) {
            logger.info("start update cache")
            notificationBoardsCacheMutex.withLock {
                notificationBoardsCache.clear()

                dao.getNotifications()
                    .map(Notification::telegramChatId)
                    .forEach { telegramChatId ->
                        addAvailablePlankaBoardsToCache(telegramChatId)
                    }
            }
            logger.info("end update cache")

            delay(NOTIFICATION_CACHE_UPDATE_DELAY)
        }
    }

    private val notificationJob = coroutineScope.launch(start = CoroutineStart.LAZY) {
        while (isActive) {
            val (stateFromPlanka, diff) = diffChannel.receive()
            logger.info("start send notifications to users")

            notificationBoardsCacheMutex.withLock {
                val allWatchedBoards = notificationBoardsCache
                    .values
                    .flatten()
                    .distinct()

                val allNotifications = dao.getNotifications()
                    .takeIf { notifications -> notifications.isNotEmpty() }
                    ?.onEach { notifications ->
                        if (notificationBoardsCache[notifications.telegramChatId] == null)
                            addAvailablePlankaBoardsToCache(notifications.telegramChatId)
                    } ?: return@withLock

                allWatchedBoards
                    .associateWith { boardId ->
                        allNotifications
                            .filter { notifications ->
                                boardId in notificationBoardsCache[notifications.telegramChatId]!!
                            }
                    }
                    .let { notificationsByBoards: Map<BoardId, List<Notification>> ->
                        diff
                            .diffCards
                            .map { entry -> entry.value.map { entry.key to it } }
                            .flatten()
                            .forEach { (action, cardId) ->
                                val card = if (action == BoardAction.DELETE)
                                    state.oldValue[stateFromPlanka.board.id]?.cards?.get(cardId)
                                else
                                    stateFromPlanka.cards[cardId]
                                if (card != null) {
                                    val boardId = card.boardId

                                    spamProtectedCardsMutex.withLock {
                                        if (card.id in spamProtectedCards &&
                                            action in listOf(BoardAction.UPDATE, BoardAction.TASK_ADD))
                                            return@forEach

                                        notificationsByBoards[boardId]?.forEach forNotifications@ { notification ->
                                            if (action in notification.watchedActions) {
                                                val maybeUser = stateFromPlanka.users[card.creatorUserId]

                                                if ((action == BoardAction.ADD || action == BoardAction.ADD_COMMENT)
                                                    && notification.userId == maybeUser?.id) {
                                                    return@forNotifications
                                                } else {
                                                    notificationBot.sendNotification(
                                                        chatId = ChatId.fromId(notification.telegramChatId),
                                                        text = when (action) {
                                                            BoardAction.ADD -> {
                                                                addBoardToSpamProtected(cardId)
                                                                buildMessage(
                                                                    state = stateFromPlanka,
                                                                    message = "Создана задача: ${card.name}\n" +
                                                                            "Пользователем: ${maybeUser?.name}\n",
                                                                    card = card,
                                                                )
                                                            }

                                                            BoardAction.UPDATE -> {
                                                                addBoardToSpamProtected(cardId)
                                                                buildMessage(
                                                                    state = stateFromPlanka,
                                                                    message = "Обновлена задача: ${card.name}\n",
                                                                    card = card,
                                                                )
                                                            }

                                                            BoardAction.MOVE ->
                                                                buildMessage(
                                                                    state = stateFromPlanka,
                                                                    message = "Задача: ${card.name}, была перемещена. " +
                                                                            "Новая позиция:\n",
                                                                    card = card,
                                                                )

                                                            BoardAction.DELETE -> buildMessage(
                                                                state = stateFromPlanka,
                                                                message = "Удалена задача: ${card.name}\n",
                                                                card = card,
                                                            )

                                                            BoardAction.TASK_ADD -> {
                                                                buildMessage(
                                                                    state = stateFromPlanka,
                                                                    message = "Добавлены новые подзадачи в задачу: ${card.name}:\n" +
                                                                            diff.addedTasks[cardId]
                                                                                ?.joinToString("\n") { it.name } + "\n\n",
                                                                    card = card,
                                                                )
                                                            }

                                                            BoardAction.TASK_REMOVE -> buildMessage(
                                                                state = stateFromPlanka,
                                                                message = "Из задачи: ${card.name} удалены подзадачи:\n" +
                                                                        diff.removedTasks[cardId]
                                                                            ?.joinToString("\n") { it.name } + "\n\n",
                                                                card = card,
                                                            )

                                                            BoardAction.TASK_COMPLETE -> buildMessage(
                                                                state = stateFromPlanka,
                                                                message = "В задаче: ${card.name}" +
                                                                        " следующие подзадачи отмечены как выполненые:\n" +
                                                                        diff.completedTasks[cardId]
                                                                            ?.joinToString("\n") { it.name } + "\n\n",
                                                                card = card,
                                                            )

                                                            BoardAction.ADD_COMMENT -> buildMessage(
                                                                state = stateFromPlanka,
                                                                message = "В задаче: ${card.name} новые коментарии:\n" +
                                                                        diff.updatedComments[cardId]
                                                                            ?.joinToString("\n") {
                                                                                (maybeUser?.name ?: "") + ": " + it.data.text
                                                                            } + "\n\n",
                                                                card = card,
                                                            )
                                                        }
                                                    )
                                                }
                                                logger.debug("Send notification to telegram channel: ${notification.telegramChatId}")
                                            }
                                        }
                                    }
                                }
                            }
                    }
            }

            logger.info("end send notifications to users")
        }
    }

    private fun buildMessage(state: PlankaData, message: String, card: CardData) = message +
            "Колонка: ${state.lists[card.listId]?.name}\n" +
            "Доска: ${state.board.name}\n"

    private fun addBoardToSpamProtected(boardId: BoardId) {
        spamProtectedCards.add(boardId)

        coroutineScope.launch {
            delay(ANTI_SPAM_DELAY)

            spamProtectedCardsMutex.withLock {
                spamProtectedCards.remove(boardId)
            }
        }
    }

    private val watchJob = coroutineScope.launch(start = CoroutineStart.LAZY) {
        while (isActive) {
            logger.info("start check planka state")
            val projects = serviceClient.projects()

            projects
                .items
                .forEach { project ->
                    projects
                        .included
                        .boards
                        .filter { it.projectId == project.id }
                        .forEach { board ->
                            logger.info("Send notification for board: ${board.name}")
                            state.setNewState(
                                boardId = board.id,
                                newState = serviceClient.loadPlankaStateForBoard(project, boardId = board.id)
                            )
                        }
                }

            logger.info("end check planka state")
            delay(PLANKA_STATE_SCAN_DELAY)
        }
    }

    private suspend fun addAvailablePlankaBoardsToCache(telegramChatId: TelegramChatId) {
        dao.getCredentialsByTelegramId(telegramChatId)
            ?.let { userPlankaCredentials ->
                val client = PlankaClient(
                    plankaUsername = userPlankaCredentials.plankaLogin,
                    plankaPassword = userPlankaCredentials.plankaPassword,
                    plankaUrl = plankaUrl,
                    maybeDisabledNotificationListNames = null,
                )

                notificationBoardsCache[userPlankaCredentials.telegramChatId] = client.projects()
                    .included
                    .boards
                    .map(Board::id)
                    .toSet()
            } ?: logger.warn("Telegram chat: $telegramChatId accept notification but don't have a planka credentials")
    }

    inner class State {
        var oldValue: ConcurrentHashMap<BoardId, PlankaData?> = ConcurrentHashMap()
            private set

        suspend fun setNewState(boardId: BoardId, newState: PlankaData) {
            if (oldValue[boardId] == null) {
                oldValue[boardId] = newState
                return
            }

            findDiff(boardId, newState)

            oldValue[boardId] = newState
        }

        private suspend fun findDiff(boardId: BoardId, newState: PlankaData) {
            val oldState = oldValue[boardId]
            require(oldState != null)

            val newDiff = StateDiff()

            newDiff.diffCards[BoardAction.ADD]?.addAll(
                newState.cards.values
                    .map(CardData::id)
                    .filter { it !in newState.movedBackCards }
                    .minus(oldState.cards.values.map(CardData::id).toSet())
            )

            newDiff.diffCards[BoardAction.DELETE]?.addAll(
                oldState.cards.values
                    .filter { it.listId !in newState.disabledListsId }
                    .map(CardData::id) -
                        newState.cards.values
                            .map(CardData::id)
                            .toSet()
            )

            newDiff.diffCards[BoardAction.MOVE]?.addAll(
                newState.cards.values
                    .filter { newCardData ->
                        oldState.cards[newCardData.id]?.listId
                            ?.let { oldListId ->
                                newCardData.listId != oldListId
                            } ?: false
                    }
                    .map(CardData::id)
                    .plus(newState.movedBackCards)
            )

            newDiff.diffCards[BoardAction.UPDATE]?.addAll(
                newState.cards.values.filter { newCardData ->
                    newCardData != oldState.cards[newCardData.id]
                }.map(CardData::id)
                    .filter { updatedCard ->
                        updatedCard !in (newDiff.diffCards[BoardAction.MOVE] ?: emptySet())
                    }
            )

            // Check tasks diff
            newDiff.diffCards[BoardAction.TASK_ADD]?.addAll(
                newState.tasks.values
                    .map(TaskData::id)
                    .filter { it !in newState.movedBackTasks }
                    .minus(oldState.tasks.values.map(TaskData::id).toSet())
                    .mapNotNull { taskId ->
                        newState.tasks[taskId]
                            ?.let { taskData ->
                                taskData.cardId
                                    .also { cardId ->
                                        newDiff.addedTasks[cardId]?.add(taskData)
                                            ?: run { newDiff.addedTasks[cardId] = mutableListOf(taskData) }
                                    }
                            }
                    }
            )

            newDiff.diffCards[BoardAction.TASK_REMOVE]?.addAll(
                (oldState.tasks.values.map(TaskData::id) - newState.tasks.values.map(TaskData::id).toSet())
                    .mapNotNull { taskId ->
                        oldState.tasks[taskId]
                            ?.let { taskData ->
                                taskData.cardId
                                    .also { cardId ->
                                        newDiff.removedTasks[cardId]?.add(taskData)
                                            ?: run { newDiff.removedTasks[cardId] = mutableListOf(taskData) }
                                    }
                            }
                    }
            )

            newDiff.diffCards[BoardAction.TASK_COMPLETE]?.addAll(
                newState.tasks.values.filter { newTaskData ->
                    newTaskData.isCompleted && oldState.tasks[newTaskData.id]?.isCompleted == false
                }.map { taskData ->
                    taskData.cardId
                        .also { cardId ->
                            newDiff.completedTasks[cardId]?.add(taskData)
                                ?: run { newDiff.completedTasks[cardId] = mutableListOf(taskData) }
                        }
                }
            )

            newDiff.diffCards[BoardAction.ADD_COMMENT]?.addAll(
                (newState.actions.keys - oldState.actions.keys)
                    .filter { actionId -> newState.actions[actionId]?.type == MAGIC_COMMENT_CARD_TYPE }
                    .mapNotNull { actionId ->
                        newState.actions[actionId]
                            ?.let { action ->
                                action.cardId
                                    .also { cardId ->
                                        newDiff.updatedComments[cardId]?.add(action)
                                            ?: run { newDiff.updatedComments[cardId] = mutableListOf(action) }
                                    }
                            }
                    }
            )

            // Filter updated cards
            newDiff.diffCards[BoardAction.UPDATE]?.removeIf { updatedCard ->
                updatedCard in (newDiff.diffCards[BoardAction.ADD] ?: emptySet())
            }

            if (newDiff.diffCards.values.flatten().isEmpty())
                return

            diffChannel.send(newState to newDiff)
        }
    }

    private class StateDiff {
        val diffCards = BoardAction.entries
            .associateWith { mutableSetOf<CardId>() }

        val updatedComments by lazy { mutableMapOf<CardId, MutableList<Action>>() }

        val removedTasks by lazy { mutableMapOf<CardId, MutableList<TaskData>>() }
        val addedTasks by lazy { mutableMapOf<CardId, MutableList<TaskData>>() }
        val completedTasks by lazy { mutableMapOf<CardId, MutableList<TaskData>>() }
    }

    enum class BoardAction {
        ADD,
        UPDATE,
        TASK_ADD,
        TASK_REMOVE,
        TASK_COMPLETE,
        ADD_COMMENT,
        MOVE,
        DELETE
    }
}
