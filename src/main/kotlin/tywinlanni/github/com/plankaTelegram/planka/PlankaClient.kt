package tywinlanni.github.com.plankaTelegram.planka

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class PlankaClient(
    plankaHost: String,
    plankaPort: String,
    plankaProtocol: String,
    private val plankaUsername: String,
    private val plankaPassword: String,
) {
    private val tokenBuffer = mutableListOf(BearerTokens("", ""))

    private val client = HttpClient(CIO) {
        defaultRequest {
            url("$plankaProtocol://$plankaHost:$plankaPort")
        }
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 3)
            retryOnException(maxRetries = 3, retryOnTimeout = true)

            delayMillis {
                3000L
            }
        }
        install(Logging) { level = LogLevel.INFO }
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
                loadTokens {
                    tokenBuffer.last()
                }
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

    suspend fun projects() = client.get("/api/projects").body<Projects>()

    suspend fun board(boardId: Long) = client.get("/api/boards/$boardId").body<BoardResponse>()
}
