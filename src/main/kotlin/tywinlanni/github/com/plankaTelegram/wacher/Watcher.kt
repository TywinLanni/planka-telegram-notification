package tywinlanni.github.com.plankaTelegram.wacher

import io.ktor.util.collections.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
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
    private val diffFlow = MutableSharedFlow<Triple<PlankaData, StateDiff, PlankaData>>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    private val notificationBoardsCache by lazy { mutableMapOf<TelegramChatId, Set<BoardId>>() }
    private val notificationBoardsCacheMutex by lazy { Mutex() }

    private val spamProtectedCards by lazy { ConcurrentSet<CardId>() }

    suspend fun start() {
        updateCacheJob.start()
        watchJob.start()
        notificationJob.start()

        job.join()
    }

    private val updateCacheJob = coroutineScope.launch(start = CoroutineStart.LAZY) {
        while (isActive) {
            logger.info("Start update cache")

            try {
                // Fetch data OUTSIDE the lock to avoid blocking notificationJob
                val telegramChatIds = dao.getNotifications()
                    .map(Notification::telegramChatId)
                
                logger.info("Fetching boards for ${telegramChatIds.size} chats")
                
                val newCache = mutableMapOf<TelegramChatId, Set<BoardId>>()
                telegramChatIds.forEach { telegramChatId ->
                    logger.info("Fetching boards for chat $telegramChatId")
                    try {
                        val boards = withTimeout(30_000L) {
                            fetchAvailablePlankaBoardsForChat(telegramChatId)
                        }
                        if (boards != null) {
                            newCache[telegramChatId] = boards
                            logger.info("Fetched ${boards.size} boards for chat $telegramChatId")
                        } else {
                            logger.warn("No boards fetched for chat $telegramChatId - possibly invalid credentials")
                        }
                    } catch (e: Exception) {
                        logger.error("Failed to fetch boards for chat $telegramChatId - possibly invalid credentials or network issue", e)
                    }
                }

                logger.info("Updating cache with ${newCache.size} entries")
                // Only hold lock for the actual cache update (fast operation)
                notificationBoardsCacheMutex.withLock {
                    notificationBoardsCache.clear()
                    notificationBoardsCache.putAll(newCache)
                }

                logger.info("End update cache")
            } catch (e: Exception) {
                logger.error("Update cache failed", e)
            }

            delay(NOTIFICATION_CACHE_UPDATE_DELAY)
        }
    }

    private val notificationJob = coroutineScope.launch(start = CoroutineStart.LAZY) {
        logger.info("Start notification job")
        diffFlow.collect { (stateFromPlanka, diff, oldState) ->
            logger.info("Start send notifications to users")

            val allNotifications = dao.getNotifications()
            logger.info("All notifications count: ${allNotifications.size}")
            
            if (allNotifications.isEmpty()) {
                logger.warn("No notifications configured, skipping")
                return@collect
            }
            
            // Skip notifications with cache miss to avoid blocking
            // updateCacheJob will populate cache on next cycle
            val notificationsWithCache = allNotifications.filter { notification ->
                val hasCachedBoards = notificationBoardsCacheMutex.withLock {
                    notificationBoardsCache[notification.telegramChatId] != null
                }
                
                if (!hasCachedBoards) {
                    logger.warn("Cache miss for ${notification.telegramChatId}, skipping notification (will be cached on next update cycle)")
                }
                
                hasCachedBoards
            }
            
            if (notificationsWithCache.isEmpty()) {
                logger.warn("No notifications with cached boards, skipping")
                logger.info("End send notifications to users")
                return@collect
            }

            notificationBoardsCacheMutex.withLock {
                val allWatchedBoards = notificationBoardsCache
                    .values
                    .flatten()
                    .distinct()
                
                logger.info("Watched boards count: ${allWatchedBoards.size}")

                allWatchedBoards
                    .associateWith { boardId ->
                        notificationsWithCache
                            .filter { notifications ->
                                boardId in notificationBoardsCache[notifications.telegramChatId]!!
                            }
                    }
                    .let { notificationsByBoards: Map<BoardId, List<Notification>> ->
                        logger.info("Notifications by boards: ${notificationsByBoards.mapValues { it.value.size }}")
                        
                        val allDiffCards = diff
                            .diffCards
                            .map { entry -> entry.value.map { entry.key to it } }
                            .flatten()
                        
                        logger.info("Total diff cards: ${allDiffCards.size}, actions: ${diff.diffCards.mapValues { it.value.size }}")
                        
                        allDiffCards.forEach { (action, cardId) ->
                                val card = if (action == BoardAction.DELETE)
                                    oldState.cards[cardId]
                                else
                                    stateFromPlanka.cards[cardId]
                                logger.info("Processing action=$action, cardId=$cardId, card=${card?.name}")
                                
                                if (card != null) {
                                    val boardId = card.boardId

                                    if (card.id in spamProtectedCards &&
                                        action in listOf(BoardAction.UPDATE, BoardAction.TASK_ADD)) {
                                        logger.info("Skipping spam-protected card: ${card.id}")
                                        return@forEach
                                    }

                                    val notificationsForBoard = notificationsByBoards[boardId]
                                    logger.info("Board $boardId has ${notificationsForBoard?.size ?: 0} notifications")
                                    
                                    notificationsForBoard?.forEach forNotifications@ { notification ->
                                        logger.info("Checking notification: chatId=${notification.telegramChatId}, watchedActions=${notification.watchedActions}, userId=${notification.userId}")
                                        
                                        if (action in notification.watchedActions) {
                                            val maybeUser = stateFromPlanka.users[card.creatorUserId]
                                            logger.info("Action $action is watched. Card creator: ${maybeUser?.id}, notification userId: ${notification.userId}")

                                            if ((action == BoardAction.ADD || action == BoardAction.ADD_COMMENT)
                                                && notification.userId == maybeUser?.id) {
                                                logger.info("Skipping self-notification for user ${notification.userId}")
                                                return@forNotifications
                                            } else {
                                                logger.info("Sending notification to ${notification.telegramChatId}")
                                                notificationBot.sendNotification(
                                                    chatId = notification.telegramChatId,
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
                                                                    " следующие подзадачи отмечены как выполненные:\n" +
                                                                    diff.completedTasks[cardId]
                                                                        ?.joinToString("\n") { it.name } + "\n\n",
                                                            card = card,
                                                        )

                                                        BoardAction.ADD_COMMENT -> buildMessage(
                                                            state = stateFromPlanka,
                                                            message = "В задаче: ${card.name} новые комментарии",
                                                            card = card,
                                                        )
                                                    }
                                                )
                                                logger.info("Notification sent successfully to ${notification.telegramChatId}")
                                            }
                                        } else {
                                            logger.info("Action $action is NOT in watchedActions: ${notification.watchedActions}")
                                        }
                                    }
                                }
                            }
                    }
            }

            logger.info("End send notifications to users")
        }
        logger.info("End notification job")
    }

    private fun buildMessage(state: PlankaData, message: String, card: CardData) = message +
            "Колонка: ${state.lists[card.listId]?.name}\n" +
            "Доска: ${state.board.name}\n"

    private fun addBoardToSpamProtected(boardId: BoardId) {
        spamProtectedCards.add(boardId)

        coroutineScope.launch {
            delay(ANTI_SPAM_DELAY)

            spamProtectedCards.remove(boardId)
        }
    }

    private val watchJob = coroutineScope.launch(start = CoroutineStart.LAZY) {
        while (isActive) {
            logger.info("Start check planka state")
            val projects = serviceClient.projects()

            projects
                ?.items
                ?.forEach { project ->
                    projects
                        .included
                        .boards
                        .filter { it.projectId == project.id }
                        .forEach { board ->
                            serviceClient.loadPlankaStateForBoard(project, boardId = board.id)
                                ?.let {
                                    logger.info("Start update state for board: ${board.name}")
                                    state.setNewState(
                                        boardId = board.id,
                                        newState = it
                                    )
                                    logger.info("End update state for board: ${board.name}")
                                } ?: logger.error("Cannot find planka state for ${board.name}")
                        }
                } ?: logger.error("Can't load projects from planka")

            logger.info("End check planka state")

            delay(PLANKA_STATE_SCAN_DELAY)
        }
    }

    private suspend fun fetchAvailablePlankaBoardsForChat(telegramChatId: TelegramChatId): Set<BoardId>? {
        logger.info("fetchAvailablePlankaBoardsForChat: Getting credentials for chat $telegramChatId")
        
        return dao.getCredentialsByTelegramId(telegramChatId)
            ?.let { userPlankaCredentials ->
                logger.info("fetchAvailablePlankaBoardsForChat: Creating PlankaClient for user ${userPlankaCredentials.plankaLogin}")
                
                val client = PlankaClient(
                    plankaUsername = userPlankaCredentials.plankaLogin,
                    plankaPassword = userPlankaCredentials.plankaPassword,
                    plankaUrl = plankaUrl,
                    maybeDisabledNotificationListNames = null,
                )

                logger.info("fetchAvailablePlankaBoardsForChat: Calling client.projects() for chat $telegramChatId")
                val projects = client.projects()
                logger.info("fetchAvailablePlankaBoardsForChat: Got projects response for chat $telegramChatId: ${projects != null}")
                
                projects
                    ?.included
                    ?.boards
                    ?.map(Board::id)
                    ?.toSet()
                    ?.also { logger.info("fetchAvailablePlankaBoardsForChat: Returning ${it.size} boards for chat $telegramChatId") }
                    ?: emptySet<BoardId>().also { logger.warn("fetchAvailablePlankaBoardsForChat: No boards found for chat $telegramChatId") }
            } ?: run {
                logger.warn("Telegram chat: $telegramChatId accept notification but don't have a planka credentials")
                null
            }
    }

    inner class State {
        var oldValue: ConcurrentHashMap<BoardId, PlankaData> = ConcurrentHashMap()
            private set

        suspend fun setNewState(boardId: BoardId, newState: PlankaData) {
            if (oldValue[boardId] == null) {
                oldValue[boardId] = newState
                return
            }

            if (oldValue[boardId] == newState) {
                logger.info("No changes for board: ${newState.board.name}")
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
                                oldState.taskList[taskData.taskListId]
                                    ?.cardId
                                    ?.also { cardId ->
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
                                oldState.taskList[taskData.taskListId]
                                    ?.cardId
                                    ?.also { cardId ->
                                        newDiff.removedTasks[cardId]?.add(taskData)
                                            ?: run { newDiff.removedTasks[cardId] = mutableListOf(taskData) }
                                    }
                            }
                    }
            )

            newDiff.diffCards[BoardAction.TASK_COMPLETE]?.addAll(
                newState.tasks.values.filter { newTaskData ->
                    newTaskData.isCompleted && oldState.tasks[newTaskData.id]?.isCompleted == false
                }.mapNotNull { taskData ->
                    newState.taskList[taskData.taskListId]
                        ?.cardId
                        ?.also { cardId ->
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

            logger.info("emit new diff")
            diffFlow.emit(
                Triple(
                    first = newState,
                    second = newDiff,
                    third = oldState,
                )
            )
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
