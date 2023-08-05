package tywinlanni.github.com.plankaTelegram

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
    private var token: String = ""

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
                    BearerTokens(token, "")
                }
            }
        }
    }

    suspend fun login() = client.submitForm("/api/access-tokens") {
        parameter(key = "emailOrUsername", value = plankaUsername)
        parameter(key = "password", value = plankaPassword)
    }.body<String>().also { println(it) }
}