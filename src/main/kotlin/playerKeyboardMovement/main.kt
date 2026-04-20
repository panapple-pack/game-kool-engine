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

data class InteractedWithHerbDoor(
    override val playerId: String,
    val doorId: String
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val newState: realGameScene.QuestState
): GameEvent

data class NpcMemoryChanged(
    override val playerId: String,
    val memory: NpcMemory
): GameEvent

data class ServerMessage(
    override val playerId: String,
    val text: String
): GameEvent


















