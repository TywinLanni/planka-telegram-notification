package tywinlanni.github.com.plankaTelegram.planka

import kotlinx.serialization.Serializable
import tywinlanni.github.com.plankaTelegram.share.BoardId
import tywinlanni.github.com.plankaTelegram.share.ProjectId

@Serializable
data class Projects(
    val items: List<Project>,
    val included: Included,
)

@Serializable
data class Project(
    val id: ProjectId,
    val name: String,
)

@Serializable
data class Included(
    val boards: List<Board>,
)

@Serializable
data class Board(
    val name: String,
    val id: BoardId,
    val projectId: Long,
)
