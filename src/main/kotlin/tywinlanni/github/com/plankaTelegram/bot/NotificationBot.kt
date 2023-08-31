package tywinlanni.github.com.plankaTelegram.bot

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.ChatId
import com.mongodb.MongoWriteException
import kotlinx.coroutines.*
import tywinlanni.github.com.plankaTelegram.db.DAO
import tywinlanni.github.com.plankaTelegram.db.Notification
import tywinlanni.github.com.plankaTelegram.db.UserPlankaCredentials
import tywinlanni.github.com.plankaTelegram.planka.PlankaClient
import tywinlanni.github.com.plankaTelegram.wacher.Watcher

class NotificationBot(
    botToken: String,
    private val dao: DAO,
    private val plankaUrl: String,
) {
    private val job = SupervisorJob()
    private val botScope = CoroutineScope(Dispatchers.IO + job)

    private val bot = bot {
        token = botToken
        myCommands()
    }

    fun startPolling() {
        bot.startPolling()
    }

    fun sendNotification(chatId: ChatId, text: String) {
        bot.sendMessage(
            chatId = chatId,
            text = text
        )
    }

    private fun Bot.Builder.myCommands() {
        dispatch {
            command("help") {
                bot.sendMessage(
                    chatId = ChatId.fromId(update.message?.chat?.id ?: return@command),
                    text = "/help - Вывести список доступных команд\n" +
                            "/login - Ввести учётные данные от аккаунта планки\n" +
                            "/addWatcher - Подключить нотификацию для отслеживания определенных действий на доступных досках\n" +
                            "/stopWatch - Приостановить нотификацию\n" +
                            "/finish - Завершить работу с ботом\n" +
                            "\n"
                )
            }

            command("login") {
                val cash = Credentials.entries
                    .associateWith { "" }
                    .toMutableMap()

                var currentStage = Credentials.LOGIN
                bot.sendMessage(chatId = ChatId.fromId(message.chat.id), text = "Введите ${currentStage.description} от аккаунта планки:")
                text {
                    cash[currentStage] = text
                    when(currentStage) {
                        Credentials.LOGIN -> {
                            currentStage = Credentials.PASSWORD
                            bot.sendMessage(chatId = ChatId.fromId(message.chat.id), text = "Введите ${currentStage.description} от аккаунта планки:")
                        }
                        Credentials.PASSWORD -> {
                            botScope.launch {
                                dao.addOrUpdateUserCredentials(
                                    UserPlankaCredentials(
                                        plankaPassword = cash[Credentials.PASSWORD] ?: return@launch,
                                        plankaLogin = cash[Credentials.LOGIN] ?: return@launch,
                                        telegramChatId = message.chat.id,
                                    )
                                )
                            }
                            bot.sendMessage(chatId = ChatId.fromId(message.chat.id), text = "Данные успешно сохранены!")
                            currentStage = Credentials.END
                        }
                        Credentials.END -> {  }
                    }

                }
                //if (currentStage == Credentials.END)
                    //removeHandler()
            }

            command("addWatcher") {
                botScope.launch {
                    dao.getCredentialsByTelegramId(
                        telegramChatId = message.chat.id,
                    ) ?: run {
                        bot.sendMessage(
                            chatId = ChatId.fromId(message.chat.id),
                            text = "В первую очередь необходимо ввести учётные данные от планки!",
                        )
                        return@launch
                    }

                    val credentials = dao.getCredentialsByTelegramId(message.chat.id)
                        ?: return@launch

                    val userId = PlankaClient(
                        plankaUrl = plankaUrl,
                        plankaUsername = credentials.plankaLogin,
                        plankaPassword = credentials.plankaPassword,
                        maybeDisabledNotificationListNames = null,
                    ).getUserData()
                        .items
                        .find { it.username == credentials.plankaLogin || it.email == credentials.plankaLogin }
                        ?.id
                        ?: return@launch

                    try {
                        dao.addNotification(
                            notification = Notification(
                                telegramChatId = message.chat.id,
                                watchedActions = Watcher.BoardAction.entries,
                                userId = userId,
                            )
                        )
                    } catch (e: MongoWriteException) {
                        bot.sendMessage(chatId = ChatId.fromId(message.chat.id), text = "Нотификация уже подключена!")
                        return@launch
                    }

                    bot.sendMessage(chatId = ChatId.fromId(message.chat.id), text = "Нотификация подключена!")
                }
            }

            command("stopWatch") {
                botScope.launch {
                    dao.deleteNotification(telegramChatId = message.chat.id)

                    bot.sendMessage(chatId = ChatId.fromId(message.chat.id), text = "Нотификация приостановлена!")
                }
            }

            command("finish") {
                botScope.launch {
                    dao.doInTransaction {
                        deleteNotification(telegramChatId = message.chat.id)
                        deletePlankaCredentials(telegramChatId = message.chat.id)
                    }

                    bot.sendMessage(chatId = ChatId.fromId(message.chat.id), text = "Работа с ботом прекращённа")
                }
            }
        }
    }

    private enum class Credentials(val description: String) {
        LOGIN("логин или почта"),
        PASSWORD("пароль"),
        END(""),
    }
}
