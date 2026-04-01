//package lesson4
//
//import de.fabmax.kool.KoolApplication           // Запускает движок
//import de.fabmax.kool.addScene                  // Функция добавления сцены (Игра, UI, Меню, Уровень)
//
//import de.fabmax.kool.math.*                    // Превращение числа в градусы (углов)
//import de.fabmax.kool.scene.*                   // Сцена, камера по умолчанию, создание фигур, освещение
//
//import de.fabmax.kool.modules.ksl.KslPbrShader  // Шейдеры - материал объекта
//import de.fabmax.kool.modules.ksl.KslShader
//import de.fabmax.kool.util.Color                // Цветовая палитра (RGBA)
//import de.fabmax.kool.util.Time                 // Время - Time.deltaT - сколько секунд пройдет между кадрами
//
//import de.fabmax.kool.pipeline.ClearColorLoad   // Чтобы не стекать элемент уже отрисованный на экране. UI - всегда поверх всего на сцене
//
//import de.fabmax.kool.modules.ui2.*             // HTML - создание текста, кнопок, панелей, Row, Column, mutableStateOf...
//import de.fabmax.kool.modules.ui2.UiModifier.*  // CSS - padding()  align()  background()  size()
//
//
//// Клиент не должен иметь возможность напрямую менять состояния здоровья, золота, квеста, диалога и т.д.
//// Клиент должен только отправлять запрос на сервер, сервер обрабатывает его запрос и возвращает ему уже измененное свойство (или отказ и бан за жульничество)
//// 1. Клиент отправляет команду-событие о том, что что-то произошло в игре
//// 2. Сервер дает ему ответ, который клиент уже без возможности подменить отображает на экране
//// Если этим пренебрегать - игру можно будет взломать, изменить и получить преимущество, либо просто сломать ее
//
//enum class QuestState {
//    START,
//    OFFERED,
//    HELP_ACCEPTED,
//    THREAD_ACCEPTED,
//    GOOD_END,
//    EVIL_END
//}
//
//data class DialogueOption(
//    val id: String,
//    val text: String
//)
//
//data class DialogueView(
//    val npcName: String,
//    val text: String,
//    val option: List<DialogueOption>
//)
//
//class Npc(
//    val id: String,
//    val npcName: String,
//) {
//    fun dialogueFor(state: QuestState): DialogueView {
//        return when (state) {
//            QuestState.START -> DialogueView(
//                npcName,
//                "Привет, нажми talk чтобы начать диалог",
//                listOf(
//                    DialogueOption("talk", "Поговорить")
//                )
//            )
//            QuestState.OFFERED -> DialogueView(
//                npcName,
//                "Будешь помогать или будем драться",
//                listOf(
//                    DialogueOption("help", "Помочь"),
//                    DialogueOption("thread", "Драться")
//                )
//            )
//            QuestState.HELP_ACCEPTED -> DialogueView(
//                npcName,
//                "Отлично, победа!",
//                emptyList()
//            )
//            QuestState.THREAD_ACCEPTED -> DialogueView(
//                npcName,
//                "Ты уверен, мабой?",
//                listOf(
//                    DialogueOption("thread_confirm", "Да, давай драться!")
//                )
//            )
//            QuestState.GOOD_END -> DialogueView(
//                npcName,
//                "Мы уже все решили, спасибо за помощь, пока!",
//                emptyList()
//            )
//            QuestState.EVIL_END -> DialogueView(
//                npcName,
//                "Не хочу драться, вали отсюда!",
//                emptyList()
//            )
//        }
//    }
//}
//
//class ClientUiState {
//    val playerId = mutableStateOf("Oleg")
//    val hp = mutableStateOf(100)
//    val gold = mutableStateOf(0)
//
//    val questState = mutableStateOf(QuestState.START)
//    val networkLagMs = mutableStateOf(350)
//    // Между серверов и клиентом всегда есть пинг, хоть и минимальный
//
//    val log = mutableStateOf<List<String>>(emptyList())
//}
//
//fun pushLog(ui: ClientUiState, text: String) {
//    ui.log.value = (ui.log.value + text).takeLast(20)
//}
//
//sealed interface GameEvent {
//    val playerId: String
//}
//
//data class TalkedToNpc(
//    override val playerId: String,
//    val npcId: String
//) : GameEvent
//
//data class ChoiceId(
//    override val playerId: String,
//    val npcId: String,
//    val choiceId: String
//) : GameEvent
//
//data class QuestStateChanged(
//    override val playerId: String,
//    val questId: String,
//    val newState: QuestState
//) : GameEvent
//
//data class PlayerProgressSaved(
//    override val playerId: String,
//    val reason: String
//) : GameEvent
//
//typealias Listener = (GameEvent) -> Unit
//
//class EventBus{
//    // Рассыльщик событий тем, кто на них подписан
//
//    // (GameEvent) -> функция, принимающая GameEvent, возвращает Unit (void) по умолчанию
//    private val listeners = mutableListOf<Listener>()    // Список слушателей
//
//    fun subscribe(listener: Listener) {
//        listeners.add(listener)
//    }
//
//    // Рассылка событий всем подписчикам
//    fun publish(event: GameEvent) {
//        for (listener in listeners) {
//            listener(event)
//        }
//    }
//}
//
//
//
//            ///////////////////////////////////////////////////////////////////////////////////////////
//                                // Команды - "Запрос клиента к серверу" //
//            ///////////////////////////////////////////////////////////////////////////////////////////
//
//sealed interface GameCommand {
//    val playerId: String
//}
//
//data class CmdTalkToNpc(
//    override val playerId: String,
//    val npc: String
//) : GameCommand
//
//data class CmdSelectedChoice(
//    override val playerId: String,
//    val npcId: String,
//    val choiceId: String
//) : GameCommand
//
//data class CmdLoadPlayer(
//    override val playerId: String
//) : GameCommand
//
//// SERVER WORLD - серверные данные и обработка клиентских команд
//
//// PlayerData - серверное состояние игрока
//data class PlayerData(
//    var hp: Int,
//    var gold: Int,
//    var questState: QuestState
//)
//
//// PendingCommand - команда, которая ждет своего выполнения (то есть симуляция пинга)
//data class PendingCommand(
//    val cmd: GameCommand,
//    var delayLeftSec: Float
//    // Сколько секунд осталось до выполнения команды
//)
//
//class ServerWorld(
//    private val bus: EventBus
//) {
//    private val questId = "q_alchemist"
//
//    // Очередь выполнения команд
//    private val inbox = mutableListOf<PendingCommand>()
//
//    private val serverPlayers = mutableMapOf<String, PlayerData>()
//
//    // Создаем игрока, если его еще нет в базе данных
//    private fun ensurePlayer(playerId: String): PlayerData {
//        val existing = serverPlayers[playerId]
//        if (existing != null) return existing
//
//        val created = PlayerData(
//            100,
//            0,
//            QuestState.START
//        )
//        serverPlayers[playerId] = created
//        return created
//    }
//
//    // Снимок серверных данных
//    fun getSnapshot(playerId: String): PlayerData {
//        val p = ensurePlayer(playerId)
//
//        return PlayerData(
//            p.hp,
//            p.gold,
//            p.questState
//        )
//    }
//
//    fun sendCommand(cmd: GameCommand, networkLagMs: Int) {
//        val lagSec = networkLagMs / 1000f
//        inbox.add(
//            PendingCommand(
//                cmd,
//                lagSec
//            )
//        )
//    }
//
//    // Вызываем каждый кадр
//    fun update(deltaSec: Float) {
//        for (pending in inbox) {
//            pending.delayLeftSec -= deltaSec
//        }
//
//        val ready = inbox.filter { it.delayLeftSec <= 0f }
//        inbox.removeAll(ready)
//
//        for (pending in ready) {
//            applyCommand(pending.cmd)
//        }
//    }
//}
//
//
//
//
//
//
//
//
//
//
//
//
//
//
//
//
//
//
//
//
//
