package tywinlanni.github.com.plankaTelegram.planka

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import tywinlanni.github.com.plankaTelegram.share.ActionId
import tywinlanni.github.com.plankaTelegram.share.CardId
import tywinlanni.github.com.plankaTelegram.share.UserId

class PlankaClient(
    plankaUrl: String,
    private val plankaUsername: String,
    private val plankaPassword: String,
) {
    private val tokenBuffer = mutableListOf<BearerTokens>()

    private val client = HttpClient(CIO) {
        defaultRequest {
            url(plankaUrl)
        }
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 3)
            retryOnException(maxRetries = 3, retryOnTimeout = true)

            delayMillis {
                3000L
            }
        }
        //install(Logging) { level = LogLevel.INFO }
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
        }
        install(Auth) {
            bearer {
                refreshTokens {
                    login()
                    tokenBuffer.last()
                }
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
            }
    }

    suspend fun projects() = client.get("/api/projects")
        .body<Projects>()

    suspend fun loadBoardData(boardId: Long) = client.get("/api/boards/$boardId")
        .body<BoardResponse>()

    suspend fun loadPlankaState(): PlankaData {
        val projects = projects()

        val cards = mutableMapOf<Long, CardData>()
        val users = mutableMapOf<Long, UserData>()
        val lists = mutableMapOf<Long, PlankaList>()
        val tasks = mutableMapOf<Long, TaskData>()
        val actions = mutableMapOf<ActionId, Action>()

        projects.included.boards
            .forEach { board ->
                loadBoardData(board.id)
                    .run {
                        cards.putAll(included.cards.associateBy { it.id })
                        users.putAll(included.users.associateBy { it.id })
                        lists.putAll(included.lists.associateBy { it.id })
                        tasks.putAll(included.tasks.associateBy { it.id })
                    }
            }

        cards.keys.forEach { cardId ->
            loadCardActions(cardId)
                .let { responseBody ->
                    actions.putAll(responseBody.items.associateBy { it.id })
                }
        }

        return PlankaData(
            projects = projects.items.associateBy { it.id },
            boards = projects.included.boards.associateBy { it.id },
            cards = cards,
            users = users,
            lists = lists,
            tasks = tasks,
            actions = actions,
        )
    }

    suspend fun loadCardActions(cardId: CardId) = client.get("/api/cards/$cardId/actions") {
        parameter(key = "withDetails", value = true)
    }.body<Actions>()

    suspend fun getUserData() = client.get("/api/users")
        .body<UsersData>()
}
