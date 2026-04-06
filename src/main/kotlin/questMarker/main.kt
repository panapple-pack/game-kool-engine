package questMarker

import de.fabmax.kool.KoolApplication              // KoolApplication - запуск приложения Kool
import de.fabmax.kool.addScene                     // addScene - добавить сцену (3D мир или UI HUD)
import de.fabmax.kool.math.Vec3f                   // Vec3f - 3D-вектор (x, y, z)
import de.fabmax.kool.math.deg                     // deg - перевод числа в градусы
import de.fabmax.kool.modules.gltf.GltfFile
import de.fabmax.kool.scene.*                      // Scene, camera, lighting, meshes
import de.fabmax.kool.modules.ksl.KslPbrShader     // KslPbrShader - готовый материал для объектов
import de.fabmax.kool.util.Color                   // Color - цвет
import de.fabmax.kool.util.Time                    // Time.deltaT - время между кадрами
import de.fabmax.kool.pipeline.ClearColorLoad      // ClearColorLoad - UI рисуется поверх 3D мира
import de.fabmax.kool.modules.ui2.*                // UI2: Text, Button, Row, Column, dp...
import de.fabmax.kool.modules.ui2.UiModifier.*     // modifier.margin / padding / align / onClick / background
import kotlinx.coroutines.coroutineScope

import kotlinx.coroutines.launch                   // launch { } - запустить корутину
import kotlinx.coroutines.flow.MutableSharedFlow   // MutableSharedFlow - поток, куда сервер отправляет команды/события
import kotlinx.coroutines.flow.SharedFlow          // SharedFlow - read-only поток
import kotlinx.coroutines.flow.asSharedFlow        // asSharedFlow() - отдать read-only версию потока
import kotlinx.coroutines.flow.MutableStateFlow    // MutableStateFlow - состояние, которое можно менять
import kotlinx.coroutines.flow.StateFlow           // StateFlow - read-only состояние
import kotlinx.coroutines.flow.asStateFlow         // asStateFlow() - read-only версия состояния

import kotlinx.coroutines.flow.collect             // collect { } - слушать поток
import kotlinx.coroutines.flow.filter              // filter { } - пропускать только подходящие элементы потока
import kotlinx.coroutines.flow.flatMapLatest       // flatMapLatest - переключать подписку при смене active player
import kotlinx.coroutines.flow.map                 // map { } - преобразовывать элементы потока
import kotlinx.coroutines.flow.onEach              // onEach { } - делать побочное действие на каждом элементе
import kotlinx.coroutines.flow.launchIn            // launchIn(scope) - запускать подписку потока в coroutineScope

import javax.print.DocFlavor
import kotlin.math.sqrt

// Flow - поток, в котором иногда проходят разные значения:
// Событие человек сделал квест*
// Новые состояние игрока и тд...

// Поток данных, который выполняется парамельно другим потокам данных - корутина
// Внутри Flow код не начинает свою работу до тех пор, пока что-то не вызовет collect
// Каждый новый collect запустит поток заново

// StateFlow - набор состояний
// Любой stateFlow - существует сам по себе. Хранит какое то одно текущее состояние, которое меняется
// Когда появляется слушатель этого состояния - он получает его нынешнее значение и все последующие обновления состояния

// SharedFlow - рассыльщик событий (радио, громкоговоритель)
// Идеален для рассылки всем подписчикам

// collect - значит слушать поток и выполнять код внутри блока события
// Выполняет код, например, пришло новое значение
// collect {...} - обработчик каждого последующего сообщения

//flow.collect {value ->
//    println(value)
//}

// collect важно запускать внутри корутины launch
// collect не завершается сам по себе

// ========= Слушатель событий ========= //

//coroutineScope.launch {
//    server.players.collect { playersMap ->
//        // Код в случае нового состояния игрока
//    }
//}

// Пример после подписки:
// 1. Кто то повлиял и обновил stateFlow игрока
// 2. collect увидит это, т.к. он уже слушает все изменения StateFlow игрока
// 3. И выполнит код внутри блока collect {playersMap -> {этот код}}

// emit - разослать событие, чтобы все collect (которые его ждут) отреагировали на это
// tryEmit = быстрая отправка события сразу без корутины

enum class QuestState {
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END
}

enum class Facing {
    LEFT,
    RIGHT,
    FORWARD,
    BACK
}

enum class WorldObjectType{
    ALCHEMIST,
    HERB_SOURCE,
    CHEST,
    DOOR
}

data class GridPos(
    val x: Int,
    val z: Int
)

data class WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    val cellX: Int,
    val cellZ: Int,
    val interactRadius: Float
)

// Память Npc - о конкретном игроке (их прогресс квеста)
data class NpcMemory(
    val hasMet: Boolean,        // Встретился ли игрок уже с NPC
    val timesTalked: Int,       // Сколько раз поговорил
    val receivedHerb: Boolean   // Отдали ли уже траву
)

data class PlayerState(
    val playerId: String,
    val gridX: Int,
    val gridZ: Int,
    val facing: Facing,
    val questState: QuestState,
    val inventory: Map<String, Int>,
    val gold: Int,
    val alchemistMemory: NpcMemory,
    val chessLooted: Boolean,
    val doorOpened: Boolean,
    val currentFocusId: String?,
    val hintText: String,
)

fun facingToYawDeg(facing: Facing): Float {
    // Угол поворота игрока по оси Y
    return when (facing) {
        Facing.FORWARD -> 0f
        Facing.RIGHT -> 90f
        Facing.BACK -> 180f
        Facing.LEFT -> 270f
    }
}

fun larp(current: Float, target: Float, t: Float): Float {
    // Линейная интерполяция нужна для плавного перемещения объекта от 1 точки к другой
    return (current + (target - current)) * t
}

fun distance2d(ax: Float, az: Float, bx: Float, bz: Float): Float{
    // Расчет расстояния между двумя точками на плоскости XZ
    // Школьная формула расстояния:
    // sqrt((dx*dx) + (dz * dz))
    val dx = ax - bx
    val dz = az - bz
    return sqrt(dx * dx + dz * dz)
}

fun initialPlayerState(playerId: String): PlayerState {
    // Разделение на нескольких игроков

    return if (playerId == "Stas"){
        PlayerState(
            "Stas",
            0,
            0,
            Facing.FORWARD,
            QuestState.START,
            emptyMap(),
            2,
            NpcMemory(
                true,
                2,
                false
            ),
            false,
            false,
            null,
            "Подойди к любой области на карте",
        )
    }else{
        PlayerState(
            "Oleg",
            0,
            0,
            Facing.FORWARD,
            QuestState.START,
            emptyMap(),
            2,
            NpcMemory(
                true,
                2,
                false
            ),
            false,
            false,
            null,
            "Подойди к любой области на карте",
        )
    }
}

data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val npcName: String,
    val text: String,
    val options: List<DialogueOption>
)

fun buildAlchemistDialogue(player: PlayerState): DialogueView {
    // Теперь показываем активный диалог только если в фокусе игрока именно алхимик

    if (player.currentFocusId != "alchemist") {
        return DialogueView(
            "Алхимик",
            "Повернись сюда, или подойди поближе",
            emptyList()
        )
    }

    val herbs = herbCount(player)
    val memory = player.alchemistMemory

    return when(player.questState){
        QuestState.START -> {
            val greeting =
                if (!memory.hasMet){
                    "Новое лицо, Я тебя не помню, зачем пришел?"
                }else{
                    "Снова ты, ${player.playerId}. Я тебя уже запомнил, ходи оглядывайся"
                }
            DialogueView(
                "Алхимик",
                "$greeting \nЕсли хочешь варить траву, для начала, собери ее 4 штуки",
                listOf(
                    DialogueOption(
                        "accept_help",
                        "Я буду варить"
                    ),
                    DialogueOption(
                        "threat",
                        "Давай сюда товар, быстро!"
                    )
                )
            )
        }

        QuestState.WAIT_HERB -> {
            if (herbs < 4){
                DialogueView(
                    "Алхимик",
                    "Пока ты собрал только $herbs/4 Травы. Возвращайся с полным товаром",
                    emptyList()
                )
            }else{
                DialogueView(
                    "Алхимик",
                    "Отличный товар, давай сюда",
                    listOf(
                        DialogueOption(
                            "give_herb",
                            "Отдать 4 травы"
                        )
                    )
                )
            }
        }

        QuestState.GOOD_END -> {
            val text =
                if (memory.receivedHerb){
                    "Спасибо, я теперь точно много зелий наварю, я тебя запомнил, заходи еще"
                }else{
                    "Ты завершил квест, но память не обновилась"
                }

            DialogueView(
                "Алхимик",
                text,
                emptyList()
            )
        }

        QuestState.EVIL_END -> {
            DialogueView(
                "Алхимик",
                "Я не хочу с тобой больше разговаривать, уходи!",
                emptyList()
            )
        }
    }
}


// ===== Команды Клиента к серверу ===== //

sealed interface GameCommand {
    val playerId: String
}

// Команда перемещения игрока
data class CmdStepMove(
    override val playerId: String,
    val stepX: Int,
    val stepZ: Int
): GameCommand

// Команда взаимодействия игрока с объектом
data class CmdInteract(
    override val playerId: String
): GameCommand

// Команда выбора варианта диалога
data class CmdChooseDialogueOption(
    override val playerId: String,
    val optionId: String
): GameCommand

data class CmdResetPlayer(
    override val playerId: String
): GameCommand



// ==== События сервер к клиенту ==== //

sealed interface GameEvent{
    val playerId: String
}

data class PlayerMoved(
    override val playerId: String,
    val newGridX: Int,
    val newGridZ: Int
): GameEvent

data class MovementBlocked(
    override val playerId: String,
    val blockedX: Int,
    val blockedZ: Int
): GameEvent

data class InteractedWithNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class InteractedWithHerbSource(
    override val playerId: String,
    val sourceId: String
): GameEvent

data class InteractedWithChest(
    override val playerId: String,
    val chestId: String
): GameEvent

data class InteractedWithDoor(
    override val playerId: String,
    val doorId: String
): GameEvent

data class InventoryChanged(
    override val playerId: String,
    val itemId: String,
    val newCount: Int
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val newState: QuestState
): GameEvent

data class NpcMemoryChanged(
    override val playerId: String,
    val memory: NpcMemory
): GameEvent

data class ServerMessage(
    override val playerId: String,
    val text: String
): GameEvent


class GameServer{

    // размеры карты
    private val minX = -5
    private val maxX = 5
    private val minZ = -4
    private val maxZ = 4

    // Статичные стены
    private val baseBlockedCells = setOf(
        GridPos(-1, 1),
        GridPos(0, 1),
        GridPos(1, 1),
        GridPos(1, 0)
    )

    // Дверь
    private val doorCell = GridPos(0, -3)

    // Список объектов мира
    val worldObjects = listOf(
        WorldObjectDef(
            "alchemist",
            WorldObjectType.ALCHEMIST,
            -3,
            0,
            1.7f,
        ),
        WorldObjectDef(
            "herb_source",
            WorldObjectType.HERB_SOURCE,
            3,
            0,
            1.7f,
        ),
        WorldObjectDef(
            "treasure_box",
            WorldObjectType.CHEST,
            0,
            3,
            1.7f,
        ),
        WorldObjectDef(
            "reward_chest",
            WorldObjectType.CHEST,
            0,
            3,
            1.3f
        ),
        WorldObjectDef(
            "door",
            WorldObjectType.DOOR,
            0,
            -3,
            1.3f
        )
    )

    // Поток событий
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    // Поток команд
    private val _commands = MutableSharedFlow<GameCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<GameCommand> = _commands.asSharedFlow()

    fun trySend(cmd: GameCommand): Boolean {
        return _commands.tryEmit(cmd)
        // tryEmit(...) - отправить команду в поток быстро, без suspend
    }

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to initialPlayerState("Oleg"),
            "Stas" to initialPlayerState("Stas")
        )
    )
    val players: StateFlow<Map<String, PlayerState>> = _players.asStateFlow()

    fun start(scope: kotlinx.coroutines.CoroutineScope){
        // Сервер слушает команды и выполняет их

        scope.launch {
            commands.collect{ cmd ->
                progressCommand(cmd)
            }
        }
    }

    fun getPlayerData(playerId: String): PlayerState {
        return _players.value[playerId] ?: initialPlayerState(playerId)
    }

    private fun setPlayerData(playerId: String, newData: PlayerState){
        val map = _players.value.toMutableMap()
        map[playerId] = newData
        _players.value = map.toMap()
    }

    fun updatePlayer(playerId: String, change: (PlayerState) -> PlayerState){
        val oldMap = _players.value
        val oldPlayer = oldMap[playerId] ?: return

        val newPlayer = change(oldPlayer)

        val newMap = oldMap.toMutableMap()
        newMap[playerId] = newPlayer
        _players.value = newMap.toMap()
    }

    private fun isCellInsideMap(x: Int, z: Int): Boolean {
        return x in minX..maxZ && z in minX..maxZ
    }

    private fun isCellBlockedForPlayer(player: PlayerState, x: Int, z: Int): Boolean {
        if (GridPos(x, z) in baseBlockedCells) return true

        if (!player.doorOpened && x == doorCell.x && z == doorCell.z) return true

        return false
    }

    private fun isObjectAvailableForPlayer(obj: WorldObjectDef, player: PlayerState): Boolean {
        return when (obj.type) {
            WorldObjectType.ALCHEMIST -> true
            WorldObjectType.HERB_SOURCE -> true
            WorldObjectType.DOOR -> true
            WorldObjectType.CHEST -> {
                player.questState == QuestState.GOOD_END && !player.chessLooted
            }
        }
    }

    // Проверка на то, смотрит ли игрок на объект, с которым взаимодействует

    private fun isObjectInFrontPlayer(player: PlayerState, obj: WorldObjectDef): Boolean {
        // Facing.LEFT - значит объект должен быть слева (dx < 0)
        // Facing.RIGHT > 0
        // FORWARD DX < 0
        // BACK DX > 0

        // val dx = obj.cellX - player.gridX
    }

    // поиск объекта ближайшего, в чью зону, попадает игрок
    private fun nearestObject(player: PlayerState): WorldObjectDef?{
        val candidates = worldObjects.filter { obj ->
            distance2d(player.gridX.toFloat(), player.gridZ.toFloat(), obj.cellX.toFloat(), obj.cellZ.toFloat()) <= obj.interactRadius
        }

        return candidates.minByOrNull { obj->
            distance2d(player.gridX.toFloat(), player.gridZ.toFloat(), obj.cellX.toFloat(), obj.cellZ.toFloat())
        }
        // minByOrNull - минимальное из возможных или null (взять ближайший объект по расстоянию по игрока
        // OrNull - если список этих объектов пуст - вернуть null
    }

    private suspend fun refreshPlayerArea(playerId: String){
        val player = getPlayerData(playerId)
        val nearest = nearestObject(player)

        val oldAreaId = player.currentAreaId
        val newAreaId = nearest?.id

        if (oldAreaId == newAreaId){
            val newHint =
                when(newAreaId){
                    "alchemist" -> "Нажми для взаимодействия"
                    "herb_source" -> "Нажми для сбора травы"
                    else -> "Подойди к объекту"
                }
            updatePlayer(playerId) { p -> p.copy(hintText = newHint)}
            return
        }
        if (oldAreaId != null){
            _events.emit(LeftArea(playerId, oldAreaId))
        }

        if (newAreaId != null){
            _events.emit(LeftArea(playerId, newAreaId))
        }

        val newHint =
            when(newAreaId){
                "alchemist" -> "Нажми для взаимодействия"
                "herb_source" -> "Нажми для сбора травы"
                else -> "Подойди к объекту"
            }
        updatePlayer(playerId) {p ->
            p.copy(
                currentAreaId = newAreaId,
                hintText = newHint
            )
        }
    }
    private suspend fun progressCommand(cmd: GameCommand){
        when(cmd){
            is CmdMovePlayer -> {
                updatePlayer(cmd.playerId){ p ->
                    p.copy(
                        posX = p.posX + cmd.dx,
                        posZ = p.posZ + cmd.dz
                    )
                }
                refreshPlayerArea(cmd.playerId)
            }
            is CmdInteract -> {
                val player = getPlayerData(cmd.playerId)
                val obj = nearestObject(player)

                if (obj == null){
                    _events.emit(ServerMessage(cmd.playerId, "Рядом нет объектов"))
                    return
                }

                when(obj.type){
                    WorldObjectType.ALCHEMIST -> {
                        val oldMemory = player.alchemistMemory
                        val newMemory = oldMemory.copy(
                            hasMet = true,
                            timesTalked = oldMemory.timesTalked + 1
                        )

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(alchemistMemory = newMemory)
                        }

                        _events.emit(InteractedWithNpc(cmd.playerId, obj.id))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                    }

                    WorldObjectType.HERB_SOURCE -> {
                        if(player.questState != QuestState.WAIT_HERB){
                            _events.emit(ServerMessage(cmd.playerId, "Тебе сейчас незачем эта трава"))
                            return
                        }

                        val oldCount = herbCount(player)
                        val newCount = oldCount + 1
                        val newInventory = player.inventory + ("herb" to newCount)

                        updatePlayer(cmd.playerId){ p ->
                            p.copy(inventory = newInventory)
                        }

                        _events.emit(InteractedWithHerbSource(cmd.playerId, obj.id))
                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                    }
                    WorldObjectType.CHEST -> {
                        if (player.questState != QuestState.GOOD_END) {
                            _events.emit(ServerMessage(cmd.playerId, "Остался без награды"))
                            return
                        }
                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.GOOD_END))
                    }
                }
            }

            is CmdChooseDialogueOption -> {
                val player = getPlayerData(cmd.playerId)

                if (player.currentAreaId != "alchemist"){
                    _events.emit(ServerMessage(cmd.playerId, "Сначала дойди до алхимика"))
                    return
                }

                when(cmd.optionId){
                    "accept_help" -> {
                        if (player.questState != QuestState.START){
                            _events.emit(ServerMessage(cmd.playerId, " Путь пока не доступен, начни диалог"))
                            return
                        }

                        updatePlayer(cmd.playerId){ p ->
                            p.copy(questState = QuestState.WAIT_HERB)
                        }

                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.WAIT_HERB))
                        _events.emit(ServerMessage(cmd.playerId, "Алхимик дал тебе задание с травой"))
                    }
                    "threat" -> {
                        if (player.questState != QuestState.START){
                            _events.emit(ServerMessage(cmd.playerId, "СНАЧАЛА ПОГОВОРИ"))
                            return
                        }

                        updatePlayer(cmd.playerId){p ->
                            p.copy(questState = QuestState.BAD_END)
                        }
                    }
                    "give_herb" -> {
                        if (player.questState != QuestState.WAIT_HERB){
                            return
                        }

                        val herbs = herbCount(player)

                        if (herbs < 4){
                            return
                        }

                        val newCount = herbs - 4
                        val newInventory = if (newCount <= 0) player.inventory - "herb" else player.inventory + ("herb" to newCount)

                        val newMemory = player.alchemistMemory.copy(
                            receivedHerb = true
                        )

                        updatePlayer(cmd.playerId){ p ->
                            p.copy(
                                inventory = newInventory,
                                questState = QuestState.GOOD_END,
                                alchemistMemory = newMemory
                            )
                        }

                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.GOOD_END))
                    }
                    else -> {
                        _events.emit(ServerMessage(cmd.playerId, "Неизвестный вариант диалога"))
                    }
                }
            }

            is CmdSwitchActivePlayer -> {
                // Дома
            }

            is CmdResetPlayer -> {
                updatePlayer(cmd.playerId) { _ -> initialPlayerState(cmd.playerId)}
                _events.emit(ServerMessage(cmd.playerId, "Игрок сброшен до заводский настроек"))
            }
        }
    }
}














