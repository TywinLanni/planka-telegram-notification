package tywinlanni.github.com

suspend fun main() {
    val plankaHost = System.getenv("PLANKA_HOST")
    val plankaPort = System.getenv("PLANKA_PORT")
    val plankaProtocol = System.getenv("PLANKA_PROTOCOL") ?: "http"

    val plankaUsername = System.getenv("PLANKA_USERNAME")
    val plankaPassword = System.getenv("PLANKA_PASSWORD")

    val client = PlankaClient(
        plankaHost = plankaHost,
        plankaPort = plankaPort,
        plankaProtocol = plankaProtocol,
        plankaUsername = plankaUsername,
        plankaPassword = plankaPassword,
    ).apply { login() }
}