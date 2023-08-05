package tywinlanni.github.com.plankaTelegram.wacher

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tywinlanni.github.com.plankaTelegram.db.DAO
import tywinlanni.github.com.plankaTelegram.planka.Board
import tywinlanni.github.com.plankaTelegram.planka.PlankaClient

class Watcher(
    private val plankaClient: PlankaClient,
    private val dao: DAO,
) {

    private val job = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + job)

    /*private val state

    suspend fun loadState(): State {

    }*/

    inner class State {
        val boards = mutableListOf(Board)
    }
}

//private val Board