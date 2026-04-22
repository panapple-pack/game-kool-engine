package playerKeyboardMovement

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
import questMarker.GameServer
import questMarker.GridPos
import questMarker.HudState


////// Импорты библиотеки desktop Keyboard bridge (JVM)  //////
import java.awt.KeyEventDispatcher
// KeyEventDispatcher - перехватчик событий клавиатуры
// То есть как объект, который видит что мы нажимаем на клавиатуре

import java.awt.KeyboardFocusManager
// KeyboardFocusManager - менеджер фокуса окна (активного окна)
// он нужен чтобы добраться до системы ввода клавиатуры в внутри активного окна windows

import java.awt.event.KeyEvent
// KeyEvent - событие нажатия какой то клавиши. В нем хранится:
// - какая клавиша нажата
// - нажали или отпустили ее

// -------------- Математические формулы --------------- //

import kotlin.math.abs
// abs(x) - модуль числа x
// abs(-3) = 3

import kotlin.math.atan2
// atan2(...) - функция для вычисления угла направления
// Нам нужна, чтобы понять:
// "если игрок идет в такую сторону, под каким углом он должен смотреть"

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
// cos - косинус нужен для математики высчитывания направлений

import kotlin.math.sqrt
// Квадратный корень числа
// Нужен для длины вектора и расстояний


// Это объект, который хранит состояние клавиатуры
// Очень важно!:
// Он не двигает игрока сам по себе
// Он дает ответ на вопросы:
// - клавиша сейчас зажата?
// - Клавишу только что зажали?

object DesktopKeyboardState {
    private val pressedKeys = mutableSetOf<Int>()
    // pressedKeys - набор кодов клавиш, которые сейчас удерживаются
    // Set - набор уникальных чисел

    private val justPressedKeys = mutableSetOf<Int>()
    // Набор клавиш, которые зажали вот вот только что
    // Удобно для действий вроде:
    // Любых одиночных разовых действий (открыть дверь, начать диалог, открыть суднук и т.д.)
    // Если не сделать этого, то клавиша взаимодействия Е при удержании будет срабатывать каждый кадр

    private var isInstalled = false
    // isInstalled - флаг установки перехватчика клавиатуры (установлен ли?)
    // Зачем нужен?:
    // Если вызывать install() 10 раз - можно случайно или специально повесить 10 одинаковых слушателей
    // Из за чего срабатывания будут накладываться и все будет "кликаться несколько раз"

    fun install() {
        // install - Установка слушателя клавиатуры
        // Делать это будем один раз в самом начале программы

        if (isInstalled) return

        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher(
                object : KeyEventDispatcher {
                    // object : Type {...} - это создание анонимного объекта, который сразу реализует интерфейс KeyEventDispatcher
                    // Простыми словами: "создать объект перехватчик клавиатуры прямо здесь"

                    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                        // dispatchKeyEvent(...) - метод, который будет вызываться при событии нажатия на клавишу
                        when(e.id) {
                            // Проверяем тип клавиш
                            KeyEvent.KEY_PRESSED -> {
                                // KEY_PRESSED - клавиша нажата
                                if (!pressedKeys.contains(e.keyCode)) {
                                    // contains - проверка на то, содержится ли клавиша уже в наборе клавиш, что нажата
                                    justPressedKeys.add(e.keyCode)
                                    // Код клавиши клавиатуры
                                }

                                pressedKeys.add(e.keyCode)
                            }

                            KeyEvent.KEY_RELEASED -> {
                                // KEY_RELEASED - событие, когда клавишу отпустили
                                pressedKeys.remove(e.keyCode)
                                justPressedKeys.remove(e.keyCode)
                                // Если клавишу отпустили - удалить из набора нажатых клавиш
                            }
                        }
                        return false
                        // Значит: не блокировать это событие, пусть система его видит
                    }
                }
            )
        isInstalled = true
        // Слушатель уже поставлен и слушает
    }

    fun isDown(keyCode: Int): Boolean {
        // Проверка на то, нажата ли сейчас конкретная клавиша
        return keyCode in pressedKeys
    }

    fun consumeJustPressed(keyCode: Int): Boolean {
        // Ловим клавишу один раз
        // Логика:
        // Если клавиша есть в justPressed, то вернуть true удалить ее оттуда
        return if (keyCode in justPressedKeys) {
            justPressedKeys.remove(keyCode)
        } else {
            false
        }
    }
}

enum class QuestState{
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END,
}

enum class WorldObjectType{
    ALCHEMIST,
    HERB_SOURCE,
    CHEST,
    DOOR
}

data class WorldObjectDef (
    val id: String,
    val type: WorldObjectType,
    val worldX: Float,
    val worldZ: Float,
    val interactRadius: Float
)

data class ObstacleDef(
    val centerX: Float,
    val centerZ: Float,
    val halfSize: Float
)
// Половина размера объекта удобно для определения столкновения с объектом по осям

data class NpcMemory(
    val hasMet: Boolean,
    val timeTalked: Int,
    val receiveHerb: Boolean
)

data class PlayerState(
    val playerId: String,

    val worldX: Float,
    val worldZ: Float,

    val yawDeg: Float,
    // куда смотрит игрок в градусах по оси (0 - смотрит вперед, 90 - направо, 180 - назад, 270 - налево)

    val moveSpeed: Float,

    val questState: QuestState,
    val inventory: Map<String, Int>,
    val gold: Int,

    val alchemistMemory: NpcMemory,

    val chestLooted: Boolean,
    val doorOpened: Boolean,

    val currentFocusId: String?,
    // То, на какой объект смотрит игрок для взаимодействия и может быть null если объекта нет

    val hintText: String,
    val pinnedQuestEnabled: Boolean,
    val pinnedTargetId: String?
)

fun lerp(current: Float, target: Float, t: Float): Float {
    // Линейная интерполяция нужна для плавного перемещения объекта от 1 точки к другой
    return current + (target - current) * t
}

fun distance2d(ax: Float, az: Float, bx: Float, bz: Float): Float {
    // Расчет расстояния между двумя точками на плоскости XZ
    // Школьная формула расстояния:
    // sqrt((dx*dx) + (dz * dz))
    val dx = ax - bx
    val dz = az - bz
    return sqrt(dx * dx + dz * dz)
}

fun herbCount(player: PlayerState): Int{
    // herbCount(...) - сколько herb у игрока
    return player.inventory["herb"] ?: 0
    // ?: 0 - если ключа herb нет - считаем 0
}

fun normalizeOrZero(x: Float, z: Float): Pair<Float, Float> {
    // Функция определяет произвольный вектор движения в единичный вектор
    // Зачем?
    // Если игрок зажмет W и D одновременно, то движение будет сырым (1, -1)
    // И диагональ станет быстрее прямого движения
    // Это ошибка, поэтому нормализуем вектор движения (то есть делаем его длину равной единице)

    val len = sqrt(x*x + z*z)
    // len - длина вектора

    return if (len <= 0.0001f) {
        // Если длина почти 0 - значит игрок по сути не движется
        // Чтобы не получить мусор безопасно возвращаем нулевые векторы
        0f to 0f
        // to - разделитель для создания Pair
    } else {
        (x / len) to (z / len)
        // После данной операции длина вектора станет равна примерно 1
        // то есть и направление сохранено, и скорость по диагонали не будет ломаться
    }
}

fun computeYawDirection(dirX: Float, dirZ: Float): Float {
    // Проверка, под каким углом надо смотреть игроку, если он движется в сторону dirX, dirZ
    val raw = Math.toDegrees(atan2(dirX.toDouble(), dirZ.toDouble())).toFloat()
    // atan2 позволяет, зная направление, получить угол - это и будет угол куда смотрит игрок
    //Math.toDegrees(...) - это преобразование в градусы
    // atan - возвращает угол в радианах - но нам нужны градусы
    // Double преобразовываем по двум причинам: более точные значения и atan2 ожидает именно double в виде параметров
    return if (raw < 0f) raw + 360f else raw
    // Зачем?
    // atan может вернуть угол в минусовом значении. Нам лучше держать градусы в диапазоне от 0 до 360
    // Так что, чтобы преобразовать в положительное -90 прибавляем к нему полные 360 и он становится просто 270 (90)
}

fun initialPlayerState(playerId: String): PlayerState {
    // Разделение на нескольких игроков

    return if (playerId == "Stas"){
        PlayerState(
            "Stas",
            0f,
            0f,
            0f,
            3.2f,
            QuestState.START,
            emptyMap(),
            0,
            NpcMemory(
                false,
                0,
                false
            ),
            false,
            false,
            null,
            "Иди к объекту",
            true,
            "alchemist"
        )
    }else{
        PlayerState(
            "Oleg",
            0f,
            0f,
            0f,
            3.2f,
            QuestState.START,
            emptyMap(),
            0,
            NpcMemory(
                false,
                0,
                false
            ),
            false,
            false,
            null,
            "Иди к объекту",
            true,
            "alchemist"
        )
    }
}

fun computePinnedTargetId(player: PlayerState): String? {
    if (!player.pinnedQuestEnabled) return null

    val herbs = herbCount(player)

    return when(player.questState) {
        QuestState.START -> "alchemist"
        QuestState.WAIT_HERB -> {
            if (herbs < 3) "herb_source" else "alchemist"
        }

        QuestState.GOOD_END -> {
            if (!player.chestLooted) "reward_chest"
            else if (!player.doorOpened) "door"
            else null
        }

        QuestState.EVIL_END -> null
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

fun buildAlchemistDialogue(player: PlayerState): DialogueView{

    if (player.currentFocusId != "alchemist") {
        return DialogueView(
            npcName = "Алхимик",
            text = "Подойди к Алхимику и смотри на него, чтобы говорить",
            options = emptyList()
        )
    }
    val herbs = herbCount(player)
    val memory = player.alchemistMemory

    return when(player.questState) {
        QuestState.START -> {
            val greeting =
                if (!memory.hasMet) {
                    "Новое лицо, Я тебя не помню, зачем пришел?"
                } else {
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
                if (memory.receiveHerb){
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

sealed interface GameCommand {
    val playerId: String
}

data class CmdMoveAxis(
    override val playerId: String,
    val axisX: Float,
    val axisZ: Float,
    val deltaSec: Float
): GameCommand

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

data class CmdTogglePinnedQuest(
    override val playerId: String
): GameCommand



sealed interface GameEvent{
    val playerId: String
}

data class PlayerMoved(
    override val playerId: String,
    val newWorldX: Float,
    val newWorldZ: Float,
): GameEvent

data class MovementBlocked(
    override val playerId: String,
    val blockedWorldX: Float,
    val blockedWorldZ: Float
): GameEvent

data class FocusChanged(
    override val playerId: String,
    val newFocus: String?
): GameEvent

data class PinnedTargetChanged(
    override val playerId: String,
    val newTargetId: String?
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

data class InventoryChanged(
    override val playerId: String,
    val itemId: String,
    val newCount: Int
): GameEvent


class GameServer {
    private val staticObstacles = listOf(
        ObstacleDef(centerX = -1f, centerZ = 1f, halfSize = 0.45f),
        ObstacleDef(centerX = 0f, centerZ = 1f, halfSize = 0.45f),
        ObstacleDef(centerX = 1f, centerZ = 1f, halfSize = 0.45f),
        ObstacleDef(centerX = 1f, centerZ = 0f, halfSize = 0.45f),
    )

    private val doorObstacle = ObstacleDef(centerX = 0f, centerZ = -3f, halfSize = 0.45f)
    // В закрытом состоянии дверь это тоже препятствие

    val worldObjects = listOf(
        WorldObjectDef(
            "alchemist",
            WorldObjectType.ALCHEMIST,
            -3f,
            0f,
            1.3f
        ),
        WorldObjectDef(
            "herb_source",
            WorldObjectType.HERB_SOURCE,
            3f,
            0f,
            1.3f
        ),
        WorldObjectDef(
            "reward_chest",
            WorldObjectType.CHEST,
            0f,
            3f,
            1.3f
        ),
        WorldObjectDef(
            "door",
            WorldObjectType.DOOR,
            0f,
            3f,
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

    private fun isPointInsideObstacle(x: Float, z: Float, obstacle: ObstacleDef, playerRadius: Float): Boolean {
        // Отвечает на вопрос если у игрока точка (х, у), то он задел препятсвие или нет
        return abs(x - obstacle.centerX) <= (obstacle.halfSize + playerRadius) &&
                abs(z - obstacle.centerZ) <= (obstacle.halfSize + playerRadius)
        // abs(x - obstacle.centerX) - определяем насколько мы далеко от центра препятствия по х
        // obstacle.halfSize + playerRadius - допустимая граница касания
        // Если в итоге и по х и по z он в опасной зоне - значит он задел препятствие
    }

    private fun isBlockedForPlayer(player: PlayerState, x: Float, z: Float): Boolean {
        val playerRadius = 0.22f
        // Толщина игрока

        for (obstacle in staticObstacles) {
            if (isPointInsideObstacle(x, z, obstacle, playerRadius)) return true
        }

        if (!player.doorOpened && isPointInsideObstacle(x, z, doorObstacle, playerRadius)) return true

        return false
    }

    private fun isObjectAvailableForPlayer(obj: WorldObjectDef, player: PlayerState): Boolean {
        // Метод проверки доступен ли тот или иной объект сейчас для взаимодействия с игроком

        return when(obj.type) {
            WorldObjectType.ALCHEMIST -> true
            WorldObjectType.HERB_SOURCE -> true

            WorldObjectType.CHEST -> player.questState == QuestState.GOOD_END && !player.chestLooted
            WorldObjectType.DOOR -> true
        }
    }

    private fun isObjectInFrontPlayer(player: PlayerState, obj: WorldObjectDef): Boolean {
        // Проверка, находится ли сейчас объект перед игроком

        val yawRad = Math.toRadians(player.yawDeg.toDouble())
        // yawDeg - угол взгляда игрока в градусах
        // Синус и косинус работают в радианах. Надо конвертировать для работы с ними
        val forwardX = sin(yawRad).toFloat()
        val forwardZ = (-cos(yawRad)).toFloat()
        // Можно представить стрелку из груди персонажа или игрока. Она показывает куда смотрит игрок
        // forwardX и forwardZ - координаты этой стрелки по плоскости
        val toObjX = obj.worldX - player.worldX
        val toObjZ = obj.worldZ - player.worldZ
        // Это вектор от игрока к объекту, где находится объект относительно игрока

        val distance = max(0.0001f, distance2d(player.worldX, player.worldZ, obj.worldX, obj.worldZ))
        // Расстояние до объекта

        val dirToObjX = toObjX / distance
        val dirToObjZ = toObjZ / distance
        // Нормализованное направление к объекту
        // То есть в какую сторону он от нас находится без влияния длины вектора

        val dot = forwardX * dirToObjX + forwardZ * dirToObjZ
        // dot - скалярное произведение
        // На простом уровне:
        // Это цифра отвечает на вопрос: На сколько объект впереди?
        // Если объект прямо перед игроком: dot близок к единице
        // Если объект сбоку: dot будет 0
        // Если объект сзади: dot будет отрицательный
        return dot > 0.45f
        // Если dot достаточно большой, считаем что объект спереди
        // 0.45f - это широкий конус перед игроком
        // Если сделать 0.9f - придется смотреть почти идеально в центр
        // А если сделать 0.1f - будет слишком мягко срабатывать
    }

    private fun pickInteractTarget(player: PlayerState): WorldObjectDef? {
        // Выбираем объект для взаимодействия
        val candidates = worldObjects.filter { obj ->
            isObjectAvailableForPlayer(obj, player) &&
                    distance2d(player.worldX, player.worldZ, obj.worldX, obj.worldZ) <= obj.interactRadius &&
                    isObjectInFrontPlayer(player, obj)
        }

        return candidates.minByOrNull { obj ->
            distance2d(player.worldX, player.worldZ, obj.worldX, obj.worldZ)
        }
    }

    private suspend fun refreshDerivedState(playerId: String) {
        // Пересчет вторичных состояний игрока
        // Вторичные - это те, что игрок не вводит напрямую, они выводятся из других данных
        // - Например: focus object
        // - active quest target
        // - hint text

        val player = getPlayerData(playerId)
        val target = pickInteractTarget(player)
        val newFocusId = target?.id

        val newPinnedTargetId = computePinnedTargetId(player)

        val newHint =
            when(newFocusId) {
                "alchemist" -> "E: Поговорить с алхимиком"
                "herb_source" -> "E: Собрать траву"
                "reward_chest" -> "E: Открыть сундук"
                "door" -> "E: Открыть дверь"
                else -> "WASD / стрелки клавиатуры движения, Е - взаимодействие"
            }
        val oldFocusId = player.currentFocusId
        val oldPinnedId = player.pinnedTargetId

        updatePlayer(playerId) { p ->
            p.copy(
                currentFocusId = newFocusId,
                pinnedTargetId = newPinnedTargetId,
                hintText = newHint
            )
            // copy - создает новую копию data class с изменяемыми полями
            // Удобный что главное - безопасный метод обновлять состояния
        }

        if (oldFocusId != newFocusId) {
            _events.emit(FocusChanged(playerId, newFocusId))
        }

        if (oldPinnedId != newPinnedTargetId) {
            _events.emit(PinnedTargetChanged(playerId, newPinnedTargetId))
        }
    }

    private suspend fun progressCommand(cmd: GameCommand) {
        when(cmd) {
            is CmdMoveAxis -> {
                val player = getPlayerData(cmd.playerId)
                val (dirX, dirZ) = normalizeOrZero(cmd.axisX, cmd.axisZ)
                // Возвращает пару значений нормализованных X и Z
                if (dirX == 0f && dirZ == 0f) {
                    refreshDerivedState(cmd.playerId)
                    return
                }

                val newYaw = computeYawDirection(dirX, dirZ)

                val distance = player.moveSpeed * cmd.deltaSec
                // Сколько игрок пройдет дистанции за этот кадр

                val newX = player.worldX + dirX * distance
                val newZ = player.worldZ + dirZ * distance
                // Куда игрок хочет пойти в следующем кадре

                val canMoveX = !isBlockedForPlayer(player, newX, player.worldZ)
                val canMoveZ = !isBlockedForPlayer(player, player.worldX, newZ)
                // Двигаем игрока по координатам по отдельности

                var finalX = player.worldX
                var finalZ = player.worldZ

                if (canMoveX) finalX = newX
                if (canMoveZ) finalZ = newZ

                if (!canMoveX && !canMoveZ) {
                    _events.emit(MovementBlocked(cmd.playerId, newX, newZ))
                }

                updatePlayer(cmd.playerId) { p ->
                    p.copy(
                        worldX = finalX,
                        worldZ = finalZ,
                        yawDeg = newYaw
                    )
                }

                _events.emit(PlayerMoved(cmd.playerId, finalX, finalZ))
                refreshDerivedState(cmd.playerId)
            }

            is CmdInteract -> {
                val player = getPlayerData(cmd.playerId)
                val target = pickInteractTarget(player)

                if (target == null){
                    _events.emit(ServerMessage(cmd.playerId, "Рядом нет объектов"))
                    return
                }

                when(target.type){
                    WorldObjectType.ALCHEMIST -> {
                        val oldMemory = player.alchemistMemory
                        val newMemory = oldMemory.copy(
                            hasMet = true,
                            timeTalked = oldMemory.timeTalked + 1
                        )

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(alchemistMemory = newMemory)
                        }

                        _events.emit(InteractedWithNpc(cmd.playerId, target.id))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                        refreshDerivedState(cmd.playerId)
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

                        _events.emit(InteractedWithHerbSource(cmd.playerId, target.id))
                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                    }
                    WorldObjectType.CHEST -> {
                        if (player.questState != QuestState.GOOD_END) {
                            _events.emit(ServerMessage(cmd.playerId, "Сундук пока что закрыт"))
                            return
                        }
                        if (player.chestLooted) {
                            _events.emit(ServerMessage(cmd.playerId, "Сундук уже открыт и залутан"))
                            return
                        }
                        updatePlayer(cmd.playerId) { p ->
                            p.copy(
                                gold = p.gold + 20,
                                chestLooted = true
                            )
                        }
                        _events.emit(InteractedWithChest(cmd.playerId, target.id))
                        _events.emit(ServerMessage(cmd.playerId, "Ты открыл сундук и получил 20 золота"))
                        refreshDerivedState(cmd.playerId)
                    }

                    WorldObjectType.DOOR -> {
                        if (player.questState != QuestState.GOOD_END) {
                            _events.emit(ServerMessage(cmd.playerId, "Дверь пока что закрыта"))
                            return
                        }
                        if (player.doorOpened) {
                            _events.emit(ServerMessage(cmd.playerId, "Дверь уже открыта"))
                            return
                        }
                        updatePlayer(cmd.playerId) { p ->
                            p.copy(doorOpened = true)
                        }

                        _events.emit(InteractedWithDoor(cmd.playerId, target.id))
                        _events.emit(ServerMessage(cmd.playerId, "Ты открыл дверь"))
                        refreshDerivedState(cmd.playerId)
                    }
                }
            }

            is CmdChooseDialogueOption -> {
                val player = getPlayerData(cmd.playerId)

                if (player.currentFocusId != "alchemist"){
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
                            p.copy(questState = QuestState.EVIL_END)
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
                            receiveHerb = true
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

            is CmdResetPlayer -> {
                updatePlayer(cmd.playerId) { _ -> initialPlayerState(cmd.playerId) }
                _events.emit(ServerMessage(cmd.playerId, "Игрок сброшен до заводский настроек"))
            }

            is CmdTogglePinnedQuest -> {
                updatePlayer(cmd.playerId) { p ->
                    p.copy(pinnedQuestEnabled = !p.pinnedQuestEnabled)
                }
                val after = getPlayerData(cmd.playerId)
                _events.emit(ServerMessage(cmd.playerId, "Pinned marker = ${after.pinnedQuestEnabled}"))
                refreshDerivedState(cmd.playerId)
            }
        }
    }
}

class HudState{
    val activePlayerIdFlow = MutableStateFlow("Oleg")
    val activePlayerIdUi = mutableStateOf("Oleg")

    val playerSnapShot = mutableStateOf(initialPlayerState("Oleg"))

    val log = mutableStateOf<List<String>>(emptyList())
}

fun hudLog(hud: HudState, line: String){
    hud.log.value = (hud.log.value + line).takeLast(20)
}

fun formatInventory(player: PlayerState): String{
    return if(player.inventory.isEmpty()){
        "Инвентарь: пуст"
    }else{
        "Инвентарь: " + player.inventory.entries.joinToString { "${it.key} x${it.value}"}
    }
}

fun currentObjective(player: PlayerState): String{
    val herbs = herbCount(player)

    return when(player.questState){
        QuestState.START -> "Подойди к алхимику"
        QuestState.WAIT_HERB -> {
            if (herbs < 3) "Собери 4 травы $herbs/4"
            else "У тебя достаточно травы вернись к Хайзенбергу"
        }
        QuestState.GOOD_END -> "Квест завершен на хорошую концовку"
        QuestState.EVIL_END -> "Квест завершен на плохую концовку"
    }
}

fun formatMemory(memory: NpcMemory): String{
    return "hasMet=${memory.hasMet}, talks=${memory.timeTalked}, receivedHerb=${memory.receiveHerb}"
}

fun eventToText(e: GameEvent): String{
    return when(e){
        is PlayerMoved -> "PlayerMoved x=${"%.2f".format(e.newWorldX)}, z=${"%.2f".format(e.newWorldZ)}"
        is MovementBlocked -> "MovementBlocked ${"%.2f".format(e.blockedWorldX)}, z=${"%.2f".format(e.blockedWorldZ)}"
        is FocusChanged -> "FocusChanges ${e.newFocus}"
        is PinnedTargetChanged -> "PinnedTargetChanged ${e.newTargetId}"
        is InteractedWithNpc -> "InteractedWithNpc ${e.npcId}"
        is InteractedWithHerbSource -> "InteractedWithHerbSource ${e.sourceId}"
        is InteractedWithChest -> "InteractedWithChest ${e.chestId}"
        is InteractedWithDoor -> "InteractedWithDoor ${e.doorId}"
        is InventoryChanged -> "InventoryChanged ${e.itemId} to ${e.newCount}"
        is QuestStateChanged -> "QuestStateChanged ${e.newState}"
        is NpcMemoryChanged -> "NpcMemoryChanged встретился = ${e.memory.hasMet}, сколько раз поговорил = ${e.memory.timeTalked}, Отдал траву = ${e.memory.receiveHerb}"
        is ServerMessage -> "Server ${e.text}"
    }
}


fun main() = KoolApplication {
    val hud = HudState()
    val server = GameServer()

    addScene {
        defaultOrbitCamera()

        for (x in -5..5) {
            for (z in -4..4) {
                addColorMesh {
                    generate { cube { colored() } }

                    shader = KslPbrShader {
                        color { vertexColor() }
                        metallic(0f)
                        roughness(0.35f)
                    }

                }.transform.translate(x.toFloat(), -1.2f, z.toFloat())
            }
        }

        val wallCells = listOf(
            GridPos(-1, 1),
            GridPos(0, 1),
            GridPos(1, 1),
            GridPos(1, 0),
            GridPos(2, 0)
        )

        for (wallCell in wallCells) {
            addColorMesh {
                generate { cube { colored() } }

                shader = KslPbrShader {
                    color { vertexColor() }
                    metallic(0f)
                    roughness(0.35f)
                }
            }.transform.translate(wallCell.x.toFloat(), 0f, wallCell.z.toFloat())
        }

        val playerNode = addColorMesh {
            generate { cube { colored() } }

            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.15f)
            }
        }

        val alchemistNode = addColorMesh {
            generate { cube { colored() } }

            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.15f)
            }
        }
        alchemistNode.transform.translate(-3f, 0f, 0f)

        val herbNode = addColorMesh {
            generate { cube { colored() } }

            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.15f)
            }
        }
        herbNode.transform.translate(3f, 0f, 0f)

        val chestNode = addColorMesh {
            generate { cube { colored() } }

            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.15f)
            }
        }
        chestNode.transform.translate(1000f, 0f, 1000f)

        val doorNode = addColorMesh {
            generate { cube { colored() } }

            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.15f)
            }
        }
    }
}




























