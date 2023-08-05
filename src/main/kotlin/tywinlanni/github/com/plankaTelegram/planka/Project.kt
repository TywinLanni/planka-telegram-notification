package tywinlanni.github.com.plankaTelegram.planka

import kotlinx.serialization.Serializable

@Serializable
data class Projects(
    val items: List<Project>,
    val included: Included,
)

@Serializable
data class Project(
    val id: Long,
    val name: String,
)

@Serializable
data class Included(
    val boards: List<Board>,
)

@Serializable
data class Board(
    val name: String,
    val id: Long,
    val projectId: Long,
)
