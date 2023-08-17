package tywinlanni.github.com.plankaTelegram.wacher

import com.github.kotlintelegrambot.entities.ChatId
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import tywinlanni.github.com.plankaTelegram.bot.NotificationBot
import tywinlanni.github.com.plankaTelegram.db.DAO
import tywinlanni.github.com.plankaTelegram.db.Notification
import tywinlanni.github.com.plankaTelegram.planka.*
import tywinlanni.github.com.plankaTelegram.share.BoardId
import tywinlanni.github.com.plankaTelegram.share.CardId
import tywinlanni.github.com.plankaTelegram.share.TelegramChatId

private val logger = LoggerFactory.getLogger(Watcher::class.java)
private const val NOTIFICATION_CACHE_UPDATE_DELAY = 900_000L
private const val PLANKA_STATE_SCAN_DELAY = 10_000L

class Watcher(
    private val serviceClient: PlankaClient,
    private val dao: DAO,
    private val notificationBot: NotificationBot,
    private val plankaUrl: String,
) {
    private val job = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + job)

    private val state by lazy { State() }
    private val diffChannel by lazy { Channel<Map<BoardAction, Set<CardId>>>() }

    private val notificationBoardsCache by lazy { mutableMapOf<TelegramChatId, Set<BoardId>>() }
    private val notificationBoardsCacheMutex by lazy { Mutex() }

    private val updateCacheJob = coroutineScope.launch {
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

    val notificationJob = coroutineScope.launch(start = CoroutineStart.LAZY) {
        while (isActive) {
            val diff = diffChannel.receive()
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
                            .map { entry -> entry.value.map { entry.key to it } }
                            .flatten()
                            .forEach { (action, cardId) ->
                                val card = if (action == BoardAction.DELETE)
                                    state.oldValue?.cards?.get(cardId)
                                else
                                    state.value?.cards?.get(cardId)
                                if (card != null) {
                                    val boardId = card.boardId

                                    notificationsByBoards[boardId]?.forEach { notification ->
                                        if (action in notification.watchedActions) {
                                            notificationBot.sendNotification(
                                                chatId = ChatId.fromId(notification.telegramChatId),
                                                text = when (action) {
                                                    BoardAction.ADD -> "Создана задача: ${card.name}\n" +
                                                            "Пользователем: ${state.value?.users?.get(card.creatorUserId)?.name}\n" +
                                                            "Колонка: ${state.value?.lists?.get(card.listId)?.name}\n" +
                                                            "Доска: ${state.value?.boards?.get(card.boardId)?.name}\n"

                                                    BoardAction.UPDATE -> "Обновлена задача: ${card.name}\n" +
                                                            "Колонка: ${state.value?.lists?.get(card.listId)?.name}\n" +
                                                            "Доска: ${state.value?.boards?.get(card.boardId)?.name}\n"

                                                    BoardAction.MOVE_TO_LIST ->
                                                        "Задача: ${card.name}, перемещена в колонку ${
                                                            state.value?.lists?.get(
                                                                card.listId
                                                            )?.name
                                                        }\n" +
                                                                "Доска: ${state.value?.boards?.get(card.boardId)?.name}\n"

                                                    BoardAction.DELETE -> "Удалена задача: ${card.name}\n" +
                                                            "Колонка: ${state.value?.lists?.get(card.listId)?.name}\n" +
                                                            "Доска: ${state.oldValue?.boards?.get(card.boardId)?.name}\n"
                                                }
                                            )
                                            logger.debug("Send notification to telegram channel: ${notification.telegramChatId}")
                                        }
                                    }
                                }
                            }
                    }
            }

            logger.info("end send notifications to users")
        }
    }

    val watchJob = coroutineScope.launch(start = CoroutineStart.LAZY) {
        while (isActive) {
            logger.info("start check planka state")
            state.setNewState(serviceClient.loadPlankaState())
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
                )

                notificationBoardsCache[userPlankaCredentials.telegramChatId] = client.projects()
                    .included
                    .boards
                    .map(Board::id)
                    .toSet()
            } ?: logger.warn("Telegram chat: $telegramChatId accept notification but don't have a planka credentials")
    }

    inner class State {
        var value: PlankaData? = null
            private set

        var oldValue: PlankaData? = null
            private set

        suspend fun setNewState(newState: PlankaData) {
            if (value == null) {
                value = newState
                return
            }
            findDiff(newState)

            oldValue = value
            value = newState
        }

        private suspend fun findDiff(newState: PlankaData) {
            val oldState = value
            require(oldState != null)

            val newDiff = BoardAction.entries
                .associateWith { mutableSetOf<CardId>() }

            newDiff[BoardAction.ADD]?.addAll(
                newState.cards.values.map(CardData::id) - oldState.cards.values.map(CardData::id).toSet()
            )

            newDiff[BoardAction.DELETE]?.addAll(
                oldState.cards.values.map(CardData::id) - newState.cards.values.map(CardData::id).toSet()
            )

            newDiff[BoardAction.MOVE_TO_LIST]?.addAll(
                newState.cards.values.filter { newCardData ->
                    oldState.cards[newCardData.id]?.listId
                        ?.let { oldListId ->
                            newCardData.listId != oldListId
                        } ?: false
                }.map(CardData::id)
            )

            newDiff[BoardAction.UPDATE]?.addAll(
                newState.cards.values.filter { newCardData ->
                    newCardData != oldState.cards[newCardData.id]
                }.map(CardData::id)
                    .filter { updatedCard ->
                        updatedCard !in (newDiff[BoardAction.MOVE_TO_LIST] ?: emptySet())
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
        DELETE
    }
}
