//package QuestJournal2
//
//import com.sun.source.tree.Scope
//import de.fabmax.kool.KoolApplication           // Запускает движок
//import de.fabmax.kool.addScene                 // Функция добавления сцены (Игра, UI, Меню, Уровень)
//import de.fabmax.kool.math.Vec3f
//import de.fabmax.kool.math.deg                  // Превращение числа в градусы (углов)
//import de.fabmax.kool.scene.*                   // Сцена, камера по умолчанию, создание фигур, освещение
//import de.fabmax.kool.modules.ksl.KslPbrShader  // Шейдеры - материал объекта
//import de.fabmax.kool.modules.ksl.KslShader
//import de.fabmax.kool.util.Color                // Цветовая палитра (RGBA)
//import de.fabmax.kool.util.Time                 // Время - Time.deltaT - сколько секунд пройдет между кадрами
//import de.fabmax.kool.pipeline.ClearColorLoad   // Чтобы не стекать элемент уже отрисованный на экране. UI - всегда поверх всего на сцене
//import de.fabmax.kool.modules.ui2.*             // HTML - создание текста, кнопок, панелей, Row, Column, mutableStateOf...
//import de.fabmax.kool.modules.ui2.UiModifier.*  // CSS - padding()  align()  background()  size()
//import de.fabmax.kool.pipeline.Texture
//import jdk.jfr.DataAmount
//
//import kotlinx.coroutines.Job
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.flow.Flow
//import kotlinx.coroutines.isActive
//
//import kotlinx.coroutines.flow.MutableSharedFlow    // MutableSharedFlow
//import kotlinx.coroutines.flow.SharedFlow           // SharedFlow
//import kotlinx.coroutines.flow.MutableStateFlow     // MutableStateFlow
//import kotlinx.coroutines.flow.StateFlow            // StateFlow
//import kotlinx.coroutines.flow.asSharedFlow         // asSharedFlow
//import kotlinx.coroutines.flow.asStateFlow          // asStateFlow
//import kotlinx.coroutines.flow.collect              // collect { } - слушать поток
//
//import kotlinx.coroutines.flow.filter               // фильтровать на какие события будем реагировать
//import kotlinx.coroutines.flow.flatMapLatest        // позволяет переключать потоки (для переключения потоков событий активного игрока)
//import kotlinx.coroutines.flow.map                  // преобразование события в строку (для логирования)
//import kotlinx.coroutines.flow.onEach               // сделать действия для каждого элемента события
//import kotlinx.coroutines.flow.launchIn             // запустить подписку в определенном scope
//import lesson2.GameState
//import kotlin.math.PI
//
//
//// -------- Статусы и маркеры квестов --------- //
//
//
//enum class QuestStatus{
//    ACTIVE,
//    COMPLETED
//}
//
//enum class QuestMarker{
//    NEW,
//    PINNED,
//    COMPLETED,
//    NONE  // Обычный активный
//}
//
//// ------- Запись квеста на сервере (то, что хранит и обрабатывает на сервере) -------- //
//
//data class QuestStateOnServer(
//    val questId: String,
//    val title: String,
//    val status: QuestStatus,
//    var step: Int,
//    var progressCurrent: Int,
//    val progressTarget: Int,
//    val isNew: Boolean,
//    val isPinned: Boolean
//)
//
//// ----- Запись квеста для UI ------- //
//
//data class QuestJournalEntry(
//    val questId: String,
//    val title: String,
//    val status: QuestStatus,
//    val objectiveText: String,          // Подсказка что делать дальше
//    val progressText: String,
//    val progressBar: String,
//    val marker: QuestMarker,
//    val markerHint: String
//)
//
//sealed interface GameEvent{
//    val playerId: String
//}
//
//data class ItemCollected(
//    override val playerId: String,
//    val itemId: String,
//    val countAdded: Int
//) : GameEvent
//
//data class GoldPaid(
//    override val playerId: String,
//    val amount: Int
//) : GameEvent
//
//data class ItemGivenToNpc(
//    override val playerId: String,
//    val npcId: String,
//    val itemId: String,
//    val count: Int
//) : GameEvent
//
//data class QuestJournalUpdated(
//    override val playerId: String
//) : GameEvent
//
//data class ServerMessage(
//    override val playerId: String,
//    val text: String
//) : GameEvent
//
//
//
//// --------- Команды клиента к серверу ---------- //
//
//sealed interface GameCommand{
//    val playerId: String
//}
//
//data class CmdOpenQuest(
//    override val playerId: String,
//    val questId: String
//) : GameCommand
//
//data class CmdPinQuest(
//    override val playerId: String,
//    val questId: String,
//) : GameCommand
//
//data class CmdCollectItem(
//    override val playerId: String,
//    val itemId: String,
//    val count: Int
//) : GameCommand
//
//data class CmdPayGold(
//    override val playerId: String,
//    val itemId: String,
//    val amount: Int
//) : GameCommand
//
//data class CmdGiveItemToNpc(
//    override val playerId: String,
//    val npcId: String,
//    val itemId: String,
//    val count: Int
//) : GameCommand
//
//data class CmdGiveGoldDebug(
//    override val playerId: String,
//    val amount: Int
//) : GameCommand
//
//
//// --- Информация игрока для синхронизации на сервере --- //
//data class PlayerData(
//    val playerId: String,
//    val gold: Int,
//    val inventory: Map<String, Int>
//)
//
//class QuestSystem{
//    fun objectiveFor(q: QuestStateOnServer): String{
//        return when(q.questId) {
//            "q_alchemist" -> when (q.step) {
//                0 -> "Поговори с Алхимиком"
//                1 -> "Собери траву: ${q.progressCurrent}/${q.progressTarget}"
//                2 -> "Отдай алхимику траву"
//                else -> "Квест завершен"
//            }
//            "q_guard" -> when(q.step) {
//                0 -> "Поговори со стражником"
//                1 -> "Заплати стражнику золото: ${q.progressCurrent}/${q.progressTarget}"
//                else -> "Проход открыт"
//            }
//            else -> "Неизвестный квест"
//        }
//    }
//
//    fun markHintFor(q: QuestStateOnServer): String {
//        return when (q.questId) {
//            "q_alchemist" -> when (q.step) {
//                0 -> "NPC: Алхимик"
//                1 -> "Сбор Hearb"
//                2 -> "NPC: Алхимик - Сдать квест"
//                else -> "Готово"
//            }
//            "q_guard" -> when(q.step) {
//                0 -> "NPC: Стражник"
//                1 -> "Передать золото NPC: Стражнику"
//                else -> "Готово"
//            }
//            else -> ""
//        }
//    }
//
//    fun progressBarText(current: Int, target: Int, blocks: Int = 10): String {
//        if (target <= 0) return ""
//        val ratio = current.toFloat() / target.toFloat()
//        val filled = (ratio * blocks).toInt().coerceIn(0, blocks)  // coerceIn ограничивает число в пределах от 0 до ... 10 (blocks)
//        val empty = blocks - filled
//
//        return "█".repeat(filled) + "░".repeat(empty)
//    }
//
//    fun markerFor(q: QuestStateOnServer): QuestMarker {
//        return when {
//            q.status == QuestStatus.COMPLETED -> QuestMarker.COMPLETED
//            q.isPinned -> QuestMarker.PINNED
//            q.isNew -> QuestMarker.NEW
//            else -> QuestMarker.NONE
//        }
//    }
//
//    fun toJournalEntry(q: QuestStateOnServer): QuestJournalEntry {
//        val objective = objectiveFor(q)
//        val progressText = if (q.progressTarget > 0) "${q.progressCurrent} / ${q.progressTarget}" else ""
//        val bar = if (q.progressTarget > 0) progressBarText(q.progressCurrent, q.progressTarget) else ""
//        return QuestJournalEntry(
//            q.questId,
//            q.title,
//            q.status,
//            objective,
//            progressText,
//            bar,
//            markerFor(q),
//            markHintFor(q)
//        )
//    }
//
//    fun onEvent(
//        playerId: String,
//        quests: List<QuestStateOnServer>,
//        event: GameEvent
//    ) : List<QuestStateOnServer> {
//        val copy = quests.toMutableList()
//
//        for (i in copy.indices) {
//            val q = copy[i]
//
//            if (q.status == QuestStatus.COMPLETED) continue
//
//            if (q.questId == "q_alchemist") {
//                val updated = updateAlchemistQuest(q, event)
//                copy[i] = updated
//            }
//            if (q.questId == "q_guard") {
//                val updated = updateGuardQuest(q, event)
//                copy[i] = updated
//            }
//        }
//        return copy.toList()
//    }
//
////    private fun updateAlchemistQuest(q: QuestStateOnServer, event: GameEvent): QuestStateOnServer {
////        // Автоматический переход из step 0 в step 1 (как будто он сразу говорит с NPC)
////        // Сбор травы - меняем progressCurrent по умолчанию 0 и симулируем поднятие травы, изменяя до progressTarget
////        // Создаем передачу предметов NPC, если условия удовлетворяют
////        if (q.step == 0) {
////            q.step += 1
////        }
////        when (q.step) {
////            1 -> when (event) {
////                is ItemCollected ->
////                    while (q.progressCurrent < q.progressTarget) {
////                        q.progressCurrent += 1
////                    }
////                else -> ""
////            }
////            2 -> when (event) {
////                is ItemGivenToNpc -> println("${event.npcId} передано ${event.itemId} в количестве ${event.count}")
////                else -> ""
////            }
////            else -> ""
////        }
////        q.step += 1
////        return q
////    }
////
////    private fun updateGuardQuest(q: QuestStateOnServer, event: GameEvent): QuestStateOnServer {
////        if (q.step == 0) {
////            q.step += 1
////        }
////        when (q.step) {
////            1 -> when (event) {
////                is GoldPaid -> println("Потрачено ${event.amount} золота")
////                else -> ""
////            }
////            else -> ""
////        }
////        q.step += 1
////        return q
////    }
//
//    private fun updateAlchemistQuest(q: QuestStateOnServer, event: GameEvent): QuestStateOnServer {
//        val base = if (q.step == 0) {
//            q.copy(step = 1, progressCurrent = 0, progressTarget = 3, isNew = false)
//        } else q
//
//        if (base.step == 1 && event is ItemCollected && event.itemId == "herb") {
//            val newCurrent = (base.progressCurrent + event.countAdded).coerceAtMost(base.progressTarget)
//            val progressed = base.copy(progressCurrent = newCurrent, isNew = false)
//
//            if (newCurrent >= base.progressTarget) {
//                return progressed.copy(step = 2, progressCurrent = 0, progressTarget = 0)
//            }
//            return progressed
//        }
//
//        if (base.step == 2 && event is ItemGivenToNpc && event.npcId == "alchemist" && event.itemId == "herb") {
//            val completed = base.copy(status = QuestStatus.COMPLETED, step = 3, progressCurrent = 0)
//        }
//
//        return base
//    }
//
//    private fun updateGuardQuest(q: QuestStateOnServer, event: GameEvent): QuestStateOnServer {
//        val base = if (q.step == 0) {
//            q.copy(step = 1, progressCurrent = 0, progressTarget = 5, isNew = false)
//        } else q
//
//        if (base.step == 1 && event is GoldPaid && event.amount > 0) {
//            val newCurrent = (base.progressCurrent + event.amount).coerceAtMost(base.progressTarget)
//            val progressed = base.copy(progressCurrent = newCurrent, isNew = false)
//
//            if (newCurrent >= base.progressTarget) {
//                return progressed.copy(status = QuestStatus.COMPLETED, step = 2, progressCurrent = 0, progressTarget = 0)
//            }
//            return progressed
//        }
//        return base
//    }
//}
//
//class GameServer {
//    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
//    val events: SharedFlow<GameEvent> = _events.asSharedFlow()
//
//    private val _commands = MutableSharedFlow<GameCommand>(extraBufferCapacity = 64)
//    val commands: SharedFlow<GameCommand> = _commands.asSharedFlow()
//
//    fun trySend(cmd: GameCommand): Boolean = _commands.tryEmit(cmd)
//    // tryEmit - эт быстрый способ отправить команду (без корутины)
//
//    private val _players = MutableStateFlow(
//        mapOf(
//            "Oleg" to PlayerData("Oleg", 0, emptyMap()),
//            "Stas" to PlayerData("Stas", 0, emptyMap())
//        )
//    )
//    val players: SharedFlow<Map<String, PlayerData>> = _players.asStateFlow()
//
//    private val _questsByPlayer = MutableStateFlow(
//        mapOf(
//            "Oleg" to listOf(
//                QuestStateOnServer("q_alchemist", "Помощь Хайзенбергу", QuestStatus.ACTIVE, 0, 0, 0, true, false),
//                QuestStateOnServer("q_guard", "Взятка стражнику этой двери", QuestStatus.ACTIVE, 0, 0, 0, true, false)
//            ),
//            "Stas" to listOf(
//                QuestStateOnServer("q_alchemist", "Помощь Хайзенбергу", QuestStatus.ACTIVE, 0, 0, 0, true, false),
//                QuestStateOnServer("q_guard", "Взятка стражнику этой двери", QuestStatus.ACTIVE, 0, 0, 0, true, false)
//            )
//        )
//    )
//    val questsByPlayer: StateFlow<Map<String, List<QuestStateOnServer>>> = _questsByPlayer.asStateFlow()
//
//    fun start(scope: kotlinx.coroutines.CoroutineScope, questSystem: QuestSystem) {
//        // Сервер слушает и выполняет их
//
//        scope.launch {
//            commands.collect{ cmd ->
//                progressCommand(cmd, questSystem)
//            }
//        }
//    }
//
//    private fun getPlayerData(playerId: String): PlayerData {
//        return _players.value[playerId] ?: PlayerData(playerId, 0, emptyMap())
//    }
//
//    private fun setPlayerData(playerId: String, newdata: PlayerData) {
//        val map = _players.value.toMutableMap()
//        map[playerId] = newdata
//        _players.value = map.toMap()
//    }
//
//    private fun getQuests(playerId: String): List<QuestStateOnServer> {
//        return _questsByPlayer.value[playerId] ?: emptyList()
//    }
//
//    fun setQuest(playerId: String, quests: List<QuestStateOnServer>) {
//        val map = _questsByPlayer.value.toMutableMap()
//        map[playerId] = quests
//        _questsByPlayer.value = map.toMap()
//    }
//
//    private suspend fun progressCommand(cmd: GameCommand, questSystem: QuestSystem) {
//        when(cmd) {
//            is CmdOpenQuest -> {
//                val list = getQuests(cmd.questId).toMutableList()
//                for (i in list.indices) {
//                    if (list[i].questId == cmd.questId) {
//                        list[i] = list[i].copy(isNew = false)
//                    }
//                }
//                setQuest(cmd.playerId, list)
//                _events.emit(QuestJournalUpdated(cmd.playerId))
//            }
//            is CmdPinQuest -> {
//                val list = getQuests(cmd.questId).toMutableList()
//                for (i in list.indices) {
//                    if (list[i].questId == cmd.questId) {
//                        list[i] = list[i].copy(isPinned = true)
//                    }
//                }
//                setQuest(cmd.playerId, list)
//                _events.emit(QuestJournalUpdated(cmd.playerId))
//            }
//            is CmdGiveGoldDebug -> {
//                val p = getPlayerData(cmd.playerId)
//                val updated = p.copy(gold = p.gold + cmd.amount)
//                setPlayerData(cmd.playerId, updated)
//
//                _events.emit(ServerMessage(cmd.playerId, "Выдано залото: +${cmd.amount}"))
//            }
//            is CmdCollectItem -> {
//                val p = getPlayerData(cmd.playerId)
//
//                val oldCount =  p.inventory.toMutableMap()
//                val newCount = oldCount + cmd.count
//
//                val inventory = p.inventory.toMutableMap()
//                inventory[cmd.itemId] = newCount
//
//                setPlayerData(cmd.playerId, p.copy(inventory = inventory.toMap()))
//
//                val ev = ItemCollected(cmd.playerId, cmd.itemId, cmd.count)
//                _events.emit(ev)
//
//                val newQuest = questSystem.onEvent(cmd.playerId, getQuests(cmd.playerId), ev)
//
//                _events.emit(QuestJournalUpdated(cmd.playerId))
//            }
//            is CmdPayGold -> {
//                val p = getPlayerData(cmd.playerId)
//
//                if (p.gold < cmd.amount) {
//                    _events.emit(ServerMessage(cmd.playerId, "Недостаточно золота: нужно ${cmd.amount}"))
//                    return
//                }
//
//                setPlayerData(cmd.playerId, p.copy(gold = p.gold - cmd.amount))
//
//                val ev = GoldPaid(cmd.playerId, cmd.amount)
//                _events.emit(ev)
//
//                val newQuests = questSystem.onEvent(cmd.playerId, getQuests(cmd.playerId), ev)
//                setQuest(cmd.playerId, newQuests)
//
//                _events.emit(QuestJournalUpdated(cmd.playerId))
//            }
//            is CmdGiveItemToNpc -> {
//                val p = getPlayerData(cmd.playerId)
//
//                val have = p.inventory[cmd.itemId] ?: 0
//                if (have < cmd.count) {
//                    _events.emit(ServerMessage(cmd.playerId, "Не хватает ${cmd.itemId}"))
//                    return
//                }
//
//                val newCount = have - cmd.count
//                val inventory = p.inventory.toMutableMap()
//                if (newCount <= 0) inventory.remove(cmd.itemId) else inventory[cmd.itemId] = newCount
//
//                setPlayerData(cmd.playerId, p.copy(inventory = inventory.toMap()))
//
//                val ev = ItemGivenToNpc(cmd.playerId, cmd.npcId, cmd.itemId, cmd.count)
//                _events.emit(ev)
//
//                val newQuests = questSystem.onEvent(cmd.playerId, getQuests(cmd.playerId), ev)
//                setQuest(cmd.playerId, newQuests)
//
//                _events.emit(QuestJournalUpdated(cmd.playerId))
//            }
//        }
//    }
//}
//
//class HudState {
//    val activePlayerIdFlow = MutableStateFlow("Oleg")
//    val activePlayerIdUi = mutableStateOf("Oleg")
//
//    val gold = mutableStateOf(0)
//    val inventoryText = mutableStateOf("Inventory (entry)")
//
//    val questEntries = mutableStateOf<List<QuestJournalEntry>>(emptyList())
//    val selectedQuestId = mutableStateOf<String?>(null)
//
//    val log = mutableStateOf<List<String>>(emptyList())
//}
//
//fun hudLog(hud: HudState, text: String) {
//    hud.log.value = (hud.log.value + text).takeLast(20)
//}
//
//fun markerSymbol(m: QuestMarker): String {
//    return when(m) {
//        QuestMarker.NEW -> "!"
//        QuestMarker.PINNED -> "->"
//        QuestMarker.COMPLETED -> "✔"
//        QuestMarker.NONE -> "*"
//    }
//}
//
//fun main() = KoolApplication {
//    val hud = HudState()
//    val server = GameServer()
//    val questSystem = QuestSystem()
//
//    // Сцена 3D
//    addScene {
//        defaultOrbitCamera()
//
//        addColorMesh {
//            generate { cube { colored() } }
//
//            shader = KslPbrShader {
//                color { vertexColor() }
//                metallic(0.7f)
//                roughness(0.10f)
//            }
//            onUpdate {
//                transform.rotate(45f.deg * Time.deltaT, Vec3f.Z_AXIS)
//            }
//        }
//
//        lighting.singleDirectionalLight {
//            setup(Vec3f(-1f, -1f, 1f))
//            setColor(Color.WHITE, 7f)
//        }
//
//        server.start(coroutineScope, questSystem)
//    }
//
//    addScene {
//        setupUiScene(ClearColorLoad)
//
//        // Запускаем подписку на player data (gold inventory) для активного игрока
//        coroutineScope.launch {
//            server.players.collect{map ->
//                // получаем активного игрока id
//                // Сохраням игрока по его айди из map если null возвращаем collect
//                // присваиваем кол-во золота в hud состояние
//                // присваиваем inventory к hud инвентаря
//                // joinToString{} - превращает список элементов в одну строку
//                // инвентарь если он не пуст достаточно вывести в формате Inventory: ItemId
//            }
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
