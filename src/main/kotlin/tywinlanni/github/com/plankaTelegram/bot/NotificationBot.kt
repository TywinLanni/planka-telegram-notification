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
    private val plankaUrl: String,
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
    
    private suspend fun validateCredentials(login: String, password: String): Boolean {
        return try {
            kotlinx.coroutines.withTimeout(10_000L) {
                val testClient = PlankaClient(
                    plankaUsername = login,
                    plankaPassword = password,
                    plankaUrl = plankaUrl,
                    maybeDisabledNotificationListNames = null,
                )
                
                // Try to fetch projects - if credentials are invalid, this will fail
                val projects = testClient.projects()
                projects != null
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun BotHandling.myCommands() {
        command("/help") {
            val helpText = when (chat.type) {
                "private" -> """
                    /help - Вывести список доступных команд
                    /login - Ввести учётные данные от аккаунта планки
                    /addWatcher - Подключить нотификацию для отслеживания определенных действий на доступных досках
                    /stopWatch - Приостановить нотификацию
                    /finish - Завершить работу с ботом
                """.trimIndent()
                "group", "supergroup" -> """
                    /help - Вывести список доступных команд
                    /login - Ввести учётные данные от группового аккаунта планки
                    /addWatcher - Подключить нотификацию для группы
                    /stopWatch - Приостановить нотификацию
                    /finish - Завершить работу с ботом
                    
                    Примечание: Для группы используется один общий аккаунт Planka
                """.trimIndent()
                else -> "Команды недоступны для этого типа чата"
            }
            sendMessage(text = helpText)
        }

        command("/login", next = "get_login") {
            if (chat.type in listOf("group", "supergroup")) {
                sendMessage(
                    "⚠️ ВНИМАНИЕ: Учётные данные будут видны всем участникам группы в истории сообщений!\n\n" +
                    "Рекомендуется:\n" +
                    "1. Создать отдельный аккаунт Planka для этой группы\n" +
                    "2. Дать ему доступ только к нужным доскам\n" +
                    "3. После ввода пароля удалить сообщения с учётными данными\n\n" +
                    "Введите логин от аккаунта планки:"
                )
                return@command
            }
            sendMessage("Введите логин от аккаунта планки:")
        }

        step("get_login", next = "get_password") {
            chain["login"] = text
            sendMessage("Введите пароль от аккаунта планки:")
        }

        step("get_password") {
            val login = chain["login"] ?: return@step

            sendMessage("Проверяю учётные данные...")
            
            // Validate credentials before saving
            val isValid = validateCredentials(login, text)
            
            if (!isValid) {
                sendMessage("❌ Неверные учётные данные! Не удалось войти в Planka с указанным логином и паролем.\n\nПопробуйте снова: /login")
                return@step
            }
            
            runCatching {
                dao.addOrUpdateUserCredentials(
                    UserPlankaCredentials(
                        plankaPassword = text,
                        plankaLogin = login,
                        telegramChatId = chat.id,
                    )
                )
            }.onFailure {
                sendMessage("❌ Ошибка при сохранении данных в базу: ${it.message}")
            }.onSuccess {
                val successMessage = when (chat.type) {
                    "private" -> "✅ Данные успешно сохранены!"
                    "group", "supergroup" -> "✅ Данные успешно сохранены!\n\n⚠️ Рекомендуется удалить сообщения с учётными данными из истории чата."
                    else -> "✅ Данные успешно сохранены!"
                }
                sendMessage(successMessage)
            }
        }

        command("/addWatcher") {
            val credentials = dao.getCredentialsByTelegramId(chat.id)
                ?: run {
                    sendMessage("В первую очередь необходимо ввести учётные данные от планки! Используйте команду /login")
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

            val successMessage = when (chat.type) {
                "private" -> "Нотификация подключена!"
                "group", "supergroup" -> "Нотификация подключена для группы \"${chat.title}\"!"
                else -> "Нотификация подключена!"
            }
            sendMessage(successMessage)
        }

        command("/stopWatch") {
            dao.deleteNotification(telegramChatId = chat.id)
            
            val message = when (chat.type) {
                "private" -> "Нотификация приостановлена!"
                "group", "supergroup" -> "Нотификация приостановлена для группы \"${chat.title}\"!"
                else -> "Нотификация приостановлена!"
            }
            sendMessage(message)
        }

        command("/finish") {
            dao.doInTransaction {
                deleteNotification(telegramChatId = chat.id)
                deletePlankaCredentials(telegramChatId = chat.id)
            }

            val message = when (chat.type) {
                "private" -> "Работа с ботом прекращена"
                "group", "supergroup" -> "Настройки для группы \"${chat.title}\" удалены"
                else -> "Работа с ботом прекращена"
            }
            sendMessage(message)
        }
    }
}
