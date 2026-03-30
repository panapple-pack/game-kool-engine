package realGameScene

import QuestJournal2.QuestSystem
import de.fabmax.kool.KoolApplication           // Запускает движок
import de.fabmax.kool.addScene                 // Функция добавления сцены (Игра, UI, Меню, Уровень)
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg                  // Превращение числа в градусы (углов)
import de.fabmax.kool.scene.*                   // Сцена, камера по умолчанию, создание фигур, освещение
import de.fabmax.kool.modules.ksl.KslPbrShader  // Шейдеры - материал объекта
import de.fabmax.kool.modules.ksl.KslShader
import de.fabmax.kool.util.Color                // Цветовая палитра (RGBA)
import de.fabmax.kool.util.Time                 // Время - Time.deltaT - сколько секунд пройдет между кадрами
import de.fabmax.kool.pipeline.ClearColorLoad   // Чтобы не стекать элемент уже отрисованный на экране. UI - всегда поверх всего на сцене
import de.fabmax.kool.modules.ui2.*             // HTML - создание текста, кнопок, панелей, Row, Column, mutableStateOf...
import de.fabmax.kool.modules.ui2.UiModifier.*  // CSS - padding()  align()  background()  size()
import de.fabmax.kool.pipeline.Texture

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow    // MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow           // SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow     // MutableStateFlow
import kotlinx.coroutines.flow.StateFlow            // StateFlow
import kotlinx.coroutines.flow.asSharedFlow         // asSharedFlow
import kotlinx.coroutines.flow.asStateFlow          // asStateFlow

import kotlinx.coroutines.flow.collect              // collect { } - слушать поток
import kotlinx.coroutines.flow.filter               // фильтровать на какие события будем реагировать
import kotlinx.coroutines.flow.flatMapLatest        // позволяет переключать потоки (для переключения потоков событий активного игрока)
import kotlinx.coroutines.flow.map                  // преобразование события в строку (для логирования)
import kotlinx.coroutines.flow.onEach               // сделать действия для каждого элемента события
import kotlinx.coroutines.flow.launchIn             // запустить подписку в определенном scope
import kotlin.math.sqrt


// ============ Типы объектов игрового мира ============== //

enum class QuestState {
    START,
    WAIT_HERB,
    GOOD_END,
    BAD_END
}

enum class WorldObjectType {
    ALCHEMIST,
    HERB_SOURCE,
    CHEST
}

// Описание объекта в мире
data class WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    val x: Float,
    val z: Float,
    val interactRadius: Float   // расстояние взаимодействия с объектом
)

// Память нпс о конкретном игроке (их прогресс квеста)
data class NpcMemory(
    val hasMet: Boolean,      // Встретился ли игрок уже с NPC
    val timesTalked: Int,       // Сколько раз поговорил
    val receivedHerb: Boolean   // Отдал ли уже траву
)

// Состояние игрока на сервере
data class PlayerState(
    val playerId: String,
    val posX: Float,
    val posZ: Float,
    val questState: QuestState,
    val inventory: Map<String, Int>,
    val alchemistMemory: NpcMemory,
    val currentAreaId: String?,            // в какой локации находится (может быть null если ни в какой)
    val hintText: String,
    val gold: Int
)


// ========= Основные функции =========== //

fun distance2d(ax: Float, az: Float, bx: Float, bz: Float): Float {
    // Рассчет расстояния между двумя точками на плоскости XZ
    // Школьная формула расстояния:
    // sqrt((dx*dx) + (dz * dz))
    val dx = ax - bx
    val dz = az - bz
    return sqrt(dx * dx + dz * dz)
}

fun initialPlayerState(playerId: String): PlayerState {
    // Разделение на нескольких игроков
    return if (playerId == "Stas") {
        PlayerState(
            "Stas",
            0f,
            0f,
            QuestState.START,
            emptyMap(),
            NpcMemory(
                true,
                2,
                false
            ),
            null,
            "Подойди к любой области на карте",
            0
        )
    } else {
        PlayerState(
            "Oleg",
            0f,
            0f,
            QuestState.START,
            emptyMap(),
            NpcMemory(
                false,
                0,
                false
            ),
            null,
            "Подойди к любой области на карте",
            0
        )
    }
}

// ========= Диалоговая модель для Hud ========= //

data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView (
    val npcName: String,
    val text: String,
    val option: List<DialogueOption>
)

fun buildAlchemistDialogue(player: PlayerState): DialogueView {
    val herbs = herbCount(player)
    val memory = player.alchemistMemory

    return when(player.questState) {
        QuestState.START -> {
            val greeting =
                if (!memory.hasMet) {
                    "Новое лицо, я тебя не помню, зачем пришел?"
                } else {
                    "Снова ты, ${player.playerId}. Я тебя уже запомнил, ходи оглядывайся"
                }
            DialogueView(
                "Алхимик",
                "$greeting \n Если хочешь варить траву, для начало собери ее 4 штуки",
                listOf(
                    DialogueOption(
                        "accept_help",
                        "Я помогу, я буду варить"
                    ),
                    DialogueOption(
                        "Threat",
                        "Давай сюда товар, быстро"
                    )
                )
            )
        }
        QuestState.WAIT_HERB -> {
            if (herbs < 3) {
                DialogueView(
                    "Алхимик",
                    "Пока ты собрал только $herbs/4 травы. Возвращайся с полным товаром",
                    emptyList()
                )
            } else {
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
                if (memory.receivedHerb) {
                    "Спасибо, я теперь точно много зелий наварю, я тебя азпомнил, заходи еще"
                } else {
                    "Ты завершил квест, но память не обновилась"
                }
            DialogueView(
                "Алхимик",
                text,
                emptyList()
            )
        }

        QuestState.BAD_END -> {
            DialogueView(
                "Алхимик",
                "Я не хочу с тобой больше разговаривать, уходи!",
                emptyList()
            )
        }
    }
}

sealed interface GameCommand {
    val playerId: String
}

// Команда перемещения игрока
data class CmdMovePlayer(
    override val playerId: String,
    val dx: Float,
    val dz: Float
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

data class CmdSwitchActivePlayer(
    override val playerId: String,
    val newPlayerId: String
): GameCommand

data class CmdResetPlayer(
    override val playerId: String
): GameCommand

// ====== События сервер к клиенту ====== //

sealed interface GameEvent {
    val playerId: String
}

data class EnteredArea(
    override val playerId: String,
    val areaId: String
): GameEvent

data class LeftArea(
    override val playerId: String,
    val areaId: String
): GameEvent

data class InteractWithNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class InteractedWithHerbSource(
    override val playerId: String,
    val sourceId: String
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



// =========== Серверная логика мира ========== //

class GameServer {
    // Список объектов мира
    val worldObjects = listOf(
        WorldObjectDef(
            "alchemist",
            WorldObjectType.ALCHEMIST,
            -3f,
            0f,
            1.7f
        ),
        WorldObjectDef(
            "herb_source",
            WorldObjectType.HERB_SOURCE,
            3f,
            0f,
            1.7f
        ),
        WorldObjectDef(
            "treasure_box",
            WorldObjectType.CHEST,
            8f,
            0f,
            1.7f
        )
    )

    // Поток событий
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _commands = MutableSharedFlow<GameCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<GameCommand> = _commands.asSharedFlow()

    fun trySend(cmd: GameCommand): Boolean = _commands.tryEmit(cmd)
    // tryEmit - эт быстрый способ отправить команду (без корутины)

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to initialPlayerState("Oleg"),
            "Stas" to initialPlayerState("Stas")
        )
    )
    val players: StateFlow<Map<String, PlayerState>> = _players.asStateFlow()

    fun start(scope: kotlinx.coroutines.CoroutineScope) {
        // Сервер слушает и выполняет их

        scope.launch {
            commands.collect{ cmd ->
                progressCommand(cmd)
            }
        }
    }

    fun getPlayerData(playerId: String): PlayerState {
        return _players.value[playerId] ?: initialPlayerState(playerId)
    }

    private fun setPlayerData(playerId: String, newData: PlayerState) {
        val map = _players.value.toMutableMap()
        map[playerId] = newData
        _players.value = map.toMap()
    }

    fun updatePlayer(playerId: String, change: (PlayerState) -> PlayerState) {
        val oldMap = _players.value
        val oldPlayer = oldMap[playerId] ?: return

        val newPlayer = change(oldPlayer)

        val newMap = oldMap.toMutableMap()
        newMap[playerId] = newPlayer
        _players.value = newMap.toMap()
    }

    // поиск объекта ближайшего, в чью зону попадает игрок

    private fun nearestObject(player: PlayerState): WorldObjectDef? {
        val candidates = worldObjects.filter { obj ->
            distance2d(player.posX, player.posZ, obj.x, obj.z) <= obj.interactRadius
        }
        return candidates.minByOrNull { obj ->
            distance2d(player.posX, player.posZ, obj.x, obj.z)
        }
        // minByOrNull - минимальное из возможных или null (взять ближайший объект по расстоянию по игрока)
        // orNull - если список этих объектов пуст - вернуть null
    }

//    private fun refreshPlayerArea(playerId: String){
//
//        val player = getPlayerData(playerId)
//        val nearObject = nearestObject(player)
//
//        val oldArea = player.currentAreaId
//        val newArea = nearObject?.id
//
//        var newHint = ""
//
//        if (newArea == "alchemist"){
//            newHint = "Ты находися в зоне Алхимика, поговори с ним чтобы принять квест"
//        }else if(newArea == "herb_source"){
//            newHint = "Ты находися в зоне Источника травы, собери траву для алхимика"
//        } else if (newArea == "treasure_box"){
//
//        } else{
//            newHint = "Вы не находитесь в зоне активности, подойдите к новому объекту"
//        }
//
//        updatePlayer(playerId) {player ->
//            player.copy(hintText = newHint)
//        }
//    }



    private suspend fun refreshPlayerArea(playerId: String) {
        val player = getPlayerData(playerId)
        val nearest = nearestObject(player)

        val oldAreaId = player.currentAreaId
        val newAreaId = nearest?.id

        if (oldAreaId == newAreaId) {
            val newHint =
                when(newAreaId) {
                    "alchemist" -> "Нажми для взаимодействия"
                    "herb_source" -> "Нажми для сбора травы"
                    else -> "Подойди к объекту"
                }
            updatePlayer(playerId) { p -> p.copy(hintText = newHint) }
            return
        }
        if (oldAreaId != null) {
            _events.emit(LeftArea(playerId, oldAreaId))
        }
        if (newAreaId != null) {
            _events.emit(LeftArea(playerId, newAreaId))
        }

        val newHint =
            when(newAreaId) {
                "alchemist" -> "Нажми для взаимодействия"
                "herb_source" -> "Нажми для сбора травы"
                else -> "Подойди к объекту"
            }
        updatePlayer(playerId) { p ->
            p.copy(
                currentAreaId = newAreaId,
                hintText = newHint
            )
        }
    }

    private suspend fun progressCommand(cmd: GameCommand) {
        when(cmd) {
            is CmdMovePlayer -> {
                updatePlayer(cmd.playerId) { p ->
                    p.copy(
                        posX = p.posX + cmd.dx,
                        posZ = p.posZ + cmd.dz
                    )
                }
            }
            is CmdInteract -> {
                val player = getPlayerData(cmd.playerId)
                val obj = nearestObject(player)

                if (obj == null) {
                    _events.emit(ServerMessage(cmd.playerId, "Рядом нет объекта"))
                    return
                }

                when(obj.type){
                    WorldObjectType.ALCHEMIST -> {
                        val oldMemory = player.alchemistMemory
                        val newMemory = oldMemory.copy(
                            hasMet = true,
                            timesTalked = oldMemory.timesTalked + 1
                        )

                        updatePlayer(cmd.playerId) {p ->
                            p.copy(alchemistMemory = newMemory)
                        }

                        _events.emit(InteractWithNpc(cmd.playerId, obj.id))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                    }

                    WorldObjectType.HERB_SOURCE -> {
                        if (player.questState != QuestState.WAIT_HERB) {
                            _events.emit(ServerMessage(cmd.playerId, "Тебе сейчас незачем эта трава"))
                            return
                        }
                        val oldCount = herbCount(player)
                        val newCount = oldCount + 1
                        val newInventory = player.inventory + ("herb" to newCount)

                        updatePlayer(cmd.playerId) {p ->
                            p.copy(inventory = newInventory)
                        }
                        _events.emit(InteractedWithHerbSource(cmd.playerId, obj.id))
                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                    }
                    else -> ""
                }
            }
            is CmdChooseDialogueOption -> {
                val player = getPlayerData(cmd.playerId)
                if (player.currentAreaId != "alchemist") {
                    _events.emit(ServerMessage(cmd.playerId, "Сначала дойди до алхимика"))
                    return
                }

                when (cmd.optionId) {
                    "accept_help" -> {
                        if (player.questState != QuestState.START) {
                            _events.emit(ServerMessage(cmd.playerId, "Путь пока не доступен, начни диалог"))
                            return
                        }
                        updatePlayer(cmd.playerId) { p ->
                            p.copy(questState = QuestState.WAIT_HERB)
                        }
                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.WAIT_HERB))
                        _events.emit(ServerMessage(cmd.playerId, "Алхимик дал тебе задание с травой"))
                    }
                    "threat" -> {
                        if (player.questState != QuestState.START) {
                            _events.emit(ServerMessage(cmd.playerId, "Сначала поговори"))
                            return
                        }

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(questState = QuestState.BAD_END)
                        }
                    }
                    "give_herb" -> {
                        if (player.questState != QuestState.WAIT_HERB) {
                            return
                        }
                        val herbs = herbCount(player)
                        if (herbs < 4) {
                            return
                        }
                        val newCount = herbs - 4
                        val newInventory = if (newCount <= 0) player.inventory - "herb" else player.inventory + ("herb" to newCount)
                        val newMemory = player.alchemistMemory.copy(receivedHerb = true)

                        updatePlayer(cmd.playerId) { p ->
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
                updatePlayer(cmd.playerId) {_ -> initialPlayerState(cmd.playerId) }
                _events.emit(ServerMessage(cmd.playerId, "Игрок сброшен до заводских настроек"))
            }
        }
    }


}

fun herbCount(player: PlayerState): Int {
    return player.inventory["herb"] ?: 0
}

class HudState {
    val activePlayerIdFlow = mutableStateOf("Oleg")
    val activePlayerIdUi = mutableStateOf("Oleg")

    val playerSnapShot = mutableStateOf(initialPlayerState("Oleg"))

    val log = mutableStateOf<List<String>>(emptyList())
}

fun hudLog(hud: HudState, line: String) {
    hud.log.value = (hud.log.value + line).takeLast(20)
}

fun formatInventory(player: PlayerState): String {
    return if (player.inventory.isEmpty()) {
        "Инвентарь пуст"
    } else {
        "Инвентарь: " + player.inventory.entries.joinToString { "${it.key} x${it.value}" }
    }
}

fun currentObjective(player: PlayerState): String {
    val herbs = herbCount(player)

    return when(player.questState) {
        QuestState.START -> "Подойди к алхимику"
        QuestState.WAIT_HERB -> {
            if (herbs < 3) "Собери 4 травы $herbs/4"
            else "У тебя достаточно травы, вернись к Хайзенбергу"
        }
        QuestState.GOOD_END -> "Квест завершен на хорошую концовку"
        QuestState.BAD_END -> "Квест завершен на плохую концовку"
    }
}

fun currentZoneText(player: PlayerState): String {
    return when(player.currentAreaId) {
        "alchemist" -> "Локация: Хайзенберг"
        "herb_source" -> "Локация: Лаборатория травы"
        else -> "Где я"
    }
}

fun formatMemory(memory: NpcMemory): String {
    return "hasMet=${memory.hasMet}, talk=${memory.timesTalked}, receivedHerb=${memory.receivedHerb}"
}

fun eventToText(e: GameEvent): String {
    return when(e) {
        is EnteredArea -> "EnteredArea ${e.areaId}"
        is LeftArea -> "LeftArea ${e.areaId}"
        is InteractWithNpc -> "InteractedWithNpc ${e.npcId}"
        is InteractedWithHerbSource -> "InteractedWithHerbSource ${e.sourceId}"
        is InventoryChanged -> "InventoryChanged ${e.itemId} to ${e.newCount}"
        is QuestStateChanged -> "QuestStateChanged ${e.newState}"
        is NpcMemoryChanged -> "NpcMemoryChanged ${e.memory}"
        is ServerMessage -> "Server ${e.text}"
    }
}

fun main() = KoolApplication {
    val hud = HudState()
    val server = GameServer()

    addScene {
        defaultOrbitCamera()

        val playerNode = addColorMesh {
            generate {
                cube{
                    colored()
                }
            }

            shader = KslPbrShader{
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }

        val alchemistNode = addColorMesh {
            generate {
                cube{
                    colored()
                }
            }

            shader = KslPbrShader{
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }

        alchemistNode.transform.translate(-3f, 0f, 0f)

        val herbNode = addColorMesh {
            generate {
                cube{
                    colored()
                }
            }

            shader = KslPbrShader{
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }
        herbNode.transform.translate(-3f, 0f, 0f)

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }

        server.start(coroutineScope)

        // Будем хранить последнее положение игрока в пространстве для отрисовки
        // И смещать игрока сдвигать куб на разницу между прошлой новой точкой
        var lastRenderedX = 0f
        var lastRenderedZ = 0f

        playerNode.onUpdate{
            val activeId = hud.activePlayerIdFlow.value
            val player = server.getPlayerData(activeId)

            val dx = player.posX - lastRenderedX
            val dz = player.posZ - lastRenderedZ

            playerNode.transform.translate(dx, 0f, dz)

            lastRenderedX = player.posX
            lastRenderedZ = player.posZ
        }
        alchemistNode.onUpdate{
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }

        herbNode.onUpdate{
            transform.rotate(35f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
    }
    addScene {
        setupUiScene(ClearColorLoad)
        hud.activePlayerIdFlow
            .flatMapLatest { pid ->
                server.players.map { map ->
                    map[pid] ?: initialPlayerState(pid)
                }
            }
                .onEach { player ->
                    hud.playerSnapShot.value = player
                }
                .launchIn(coroutineScope)

            addPanelSurface {
                modifier
                    .align(AlignmentX.Start, AlignmentY.Top)
                    .margin(16.dp)
                    .background(RoundRectBackground(Color(0f, 0f, 0f, 0.5f), 14.dp))
                    .padding(12.dp)
                Column {
                    val player = hud.playerSnapShot.use()
                    val dialogue = buildAlchemistDialogue(player)

                    Text("Игрок ${hud.activePlayerIdUi.use()}"){modifier.margin(bottom = sizes.gap)}

                    Text("Позиция x=${"%.1f".format(player.posX)} z=${"%.1f".format(player.posZ)}"){}
                    Text(currentZoneText(player)){modifier.font(sizes.smallText)}

                    Text("QuestState: ${player.questState}"){modifier.font(sizes.smallText)}
                    Text(currentObjective(player)){modifier.margin(bottom = sizes.gap)}
                    Text(formatInventory(player)){modifier.font(sizes.smallText)}
                    Text("Hint: ${player.hintText}"){}

                    Row {
                        Button("Лево") {
                            modifier.onClick {
                                server.trySend(CmdMovePlayer(player.playerId, dx = -0.5f, dz = 0f))
                            }
                        }
                        Button("Право") {
                            modifier.onClick {
                                server.trySend(CmdMovePlayer(player.playerId, dx = 0.5f, dz = 0f))
                            }
                        }
                        Button("Вперед") {
                            modifier.onClick {
                                server.trySend(CmdMovePlayer(player.playerId, dx = 0f, dz = -0.5f))
                            }
                        }
                        Button("Назад") {
                            modifier.onClick {
                                server.trySend(CmdMovePlayer(player.playerId, dx = 0f, dz = 0.5f))
                            }
                        }
                    }
                }
            }
        }
    }























