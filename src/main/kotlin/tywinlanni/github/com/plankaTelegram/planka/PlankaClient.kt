package tywinlanni.github.com.plankaTelegram.planka

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import tywinlanni.github.com.plankaTelegram.share.ActionId
import tywinlanni.github.com.plankaTelegram.share.CardId
import tywinlanni.github.com.plankaTelegram.share.ListId
import tywinlanni.github.com.plankaTelegram.share.TaskId

private val logger = LoggerFactory.getLogger(PlankaClient::class.java)

class PlankaClient(
    plankaUrl: String,
    private val plankaUsername: String,
    private val plankaPassword: String,
    private val maybeDisabledNotificationListNames: List<String>?,
) {
    private val tokenBuffer = arrayListOf<BearerTokens>().apply {
        ensureCapacity(5)
    }

    private val disabledListsId by lazy { hashSetOf<ListId>() }
    private val disabledCardId by lazy { hashSetOf<CardId>() }
    private val invalidCardId by lazy { hashSetOf<CardId>() }
    private val disabledTaskId by lazy { hashSetOf<TaskId>() }

    private val client = HttpClient(OkHttp) {
        defaultRequest {
            url(plankaUrl)
        }
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 2_000
            socketTimeoutMillis = 2_000
            requestTimeoutMillis = 2_000
        }
        install(Auth) {
            bearer {
                refreshTokens {
                    login()
                    tokenBuffer.last()
                }

                sendWithoutRequest { true }
            }
        }
    }

    private suspend fun login() {
        client.submitForm("/api/access-tokens") {
            parameter(key = "emailOrUsername", value = plankaUsername)
            parameter(key = "password", value = plankaPassword)
        }.body<Token>()
            .let { token ->
                tokenBuffer.add(
                    BearerTokens(accessToken = token.item, refreshToken = "")
                )

                if (tokenBuffer.size > 5)
                    tokenBuffer.removeFirst()
            }
    }

    suspend fun projects(): Projects? =
        runCatching {
            client.get("/api/projects")
                .body<Projects>()
        }.getOrNull()

    private suspend fun loadBoardData(boardId: Long) = client.get("/api/boards/$boardId")
        .body<BoardResponse>()

    suspend fun loadPlankaStateForBoard(project: Project, boardId: Long): PlankaData? = runCatching {
        val cards = mutableMapOf<Long, CardData>()
        val users = mutableMapOf<Long, UserData>()
        val lists = mutableMapOf<Long, PlankaList>()
        val tasks = mutableMapOf<Long, TaskData>()
        val actions = mutableMapOf<ActionId, Action>()
        val movedBackCards = mutableSetOf<CardId>()
        val movedBackTasks = mutableSetOf<CardId>()

        val board = loadBoardData(boardId)
            .apply {
                lists.putAll(
                    included.lists
                        .doIfDisabledListsExist { lists ->
                            lists.onEach { list ->
                                if (list.name in maybeDisabledNotificationListNames!!) {
                                    disabledListsId.add(list.id)
                                }
                            }
                        }
                        .associateBy { it.id }
                )
                cards.putAll(
                    included.cards
                        .doIfDisabledListsExist { cards ->
                            cards
                                .filter { cardData ->
                                    if (cardData.id in disabledCardId) {
                                        if (cardData.listId !in disabledListsId) {
                                            disabledCardId.remove(cardData.id)
                                            movedBackCards.add(cardData.id)
                                            true
                                        } else {
                                            false
                                        }
                                    } else {
                                        if (cardData.listId in disabledListsId)
                                            disabledCardId.add(cardData.id)
                                        true
                                    }
                                }
                        }
                        .filter { it.id !in invalidCardId }
                        .associateBy { it.id }
                )
                tasks.putAll(
                    included.tasks
                        .doIfDisabledListsExist { tasks ->
                            tasks
                                .filter { taskData ->
                                    if (taskData.id in disabledTaskId) {
                                        if (taskData.cardId !in disabledCardId) {
                                            disabledTaskId.remove(taskData.id)
                                            movedBackTasks.add(taskData.id)
                                            true
                                        } else {
                                            false
                                        }
                                    } else {
                                        if (taskData.cardId in disabledCardId)
                                            disabledTaskId.add(taskData.id)
                                        true
                                    }
                                }
                        }
                        .associateBy { it.id }
                )

                users.putAll(included.users.associateBy { it.id })
            }

        // TODO do it as parallel job
        cards.keys.forEach { cardId ->
            loadCardActions(cardId)
                ?.let { responseBody ->
                    actions.putAll(responseBody.items.associateBy { it.id })
                }
        }

        return PlankaData(
            project = project,
            board = board.item,
            cards = cards,
            users = users,
            lists = lists,
            tasks = tasks,
            actions = actions,
            disabledListsId = disabledListsId,
            movedBackCards = movedBackCards,
            movedBackTasks = movedBackTasks,
        )
    }.getOrElse {
        logger.error(it.stackTraceToString())
        null
    }

    private suspend fun loadCardActions(cardId: CardId) = client.get("/api/cards/$cardId/actions") {
        parameter(key = "withDetails", value = false)
    }.runCatching {
        // todo [tywin lanni 03.09.23] завести ишью (мб проблема после перемещения доски на другую доску)
        if (status == HttpStatusCode.NotFound)
            error("Card with id: $cardId not found")
        body<Actions>()
    }.onFailure {
        // TODO: [tywin lanni 03.09.23] подумать над заведением отдельного списка с проблемными картами
        logger.debug("For load actions from card: $cardId returns invalid answer: ${it.message}$")
        invalidCardId.add(cardId)
    }.getOrNull()

    suspend fun getUserData() = runCatching {
        client.get("/api/users")
            .body<UsersData>()
    }.getOrNull()


    private inline fun <T> List<T>.doIfDisabledListsExist(block: (List<T>) -> List<T>): List<T> =
        this.let {
            if (maybeDisabledNotificationListNames != null) {
                return@let block(this)
            }

            this
        }
}
