package tywinlanni.github.com.plankaTelegram.bot

import com.mongodb.MongoWriteException
import io.github.dehuckakpyt.telegrambot.config.TelegramBotConfig
import io.github.dehuckakpyt.telegrambot.ext.config.receiver.handling
import io.github.dehuckakpyt.telegrambot.factory.TelegramBotFactory
import io.github.dehuckakpyt.telegrambot.handling.BotHandling
import tywinlanni.github.com.plankaTelegram.db.DAO
import tywinlanni.github.com.plankaTelegram.db.Notification
import tywinlanni.github.com.plankaTelegram.db.UserPlankaCredentials
import tywinlanni.github.com.plankaTelegram.planka.PlankaClient
import tywinlanni.github.com.plankaTelegram.wacher.Watcher

class NotificationBot(
    private val botToken: String,
    private val botName: String,
    private val dao: DAO,
    private val plankaClient: PlankaClient,
) : TelegramBot {
    val config = TelegramBotConfig().apply {
        token = botToken
        username = botName

        receiving {
            handling {
                myCommands()
            }
        }
    }

    val context = TelegramBotFactory.createTelegramBotContext(config)
    val updateReceiver = context.updateReceiver

    private val chain: MutableMap<String, String> = mutableMapOf()

    fun startPolling() {
        updateReceiver.start()
    }

    override suspend fun sendNotification(chatId: Long, text: String) {
        context.telegramBot.sendMessage(
            chatId = chatId,
            text = text
        )
    }

    private fun BotHandling.myCommands() {
        command("/help") {
            sendMessage(
                text = "/help - Вывести список доступных команд\n" +
                        "/login - Ввести учётные данные от аккаунта планки\n" +
                        "/addWatcher - Подключить нотификацию для отслеживания определенных действий на доступных досках\n" +
                        "/stopWatch - Приостановить нотификацию\n" +
                        "/finish - Завершить работу с ботом\n" +
                        "\n"
            )
        }

        command("/login", next = "get_login") {
            sendMessage("Введите логин от аккаунта планки:")
        }

        step("get_login", next = "get_password") {
            chain["login"] = text
            sendMessage("Введите пароль от аккаунта планки:")
        }

        step("get_password") {
            val login = chain["login"] ?: return@step

            dao.addOrUpdateUserCredentials(
                UserPlankaCredentials(
                    plankaPassword = text,
                    plankaLogin = login,
                    telegramChatId = chat.id,
                )
            )

            sendMessage("Данные успешно сохранены!")
        }

        command("/addWatcher") {
            val credentials = dao.getCredentialsByTelegramId(chat.id)
                ?: run {
                    sendMessage("В первую очередь необходимо ввести учётные данные от планки!")
                    return@command
                }

            val userId = plankaClient.getUserData()
                ?.items
                ?.find { it.username == credentials.plankaLogin }
                ?.id
                ?: run {
                    sendMessage("Пользователь с name: ${credentials.plankaLogin} not found!")
                    return@command
                }

            try {
                dao.addNotification(
                    notification = Notification(
                        telegramChatId = chat.id,
                        watchedActions = Watcher.BoardAction.entries,
                        userId = userId,
                    )
                )
            } catch (e: MongoWriteException) {
                sendMessage("Нотификация уже подключена!")
                return@command
            }

            sendMessage("Нотификация подключена!")
        }

        command("/stopWatch") {
            dao.deleteNotification(telegramChatId = chat.id)
            sendMessage("Нотификация приостановлена!")
        }

        command("/finish") {
            dao.doInTransaction {
                deleteNotification(telegramChatId = chat.id)
                deletePlankaCredentials(telegramChatId = chat.id)
            }

            sendMessage("Работа с ботом прекращёна")
        }
    }
}
