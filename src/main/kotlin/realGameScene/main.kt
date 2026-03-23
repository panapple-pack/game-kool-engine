package realGameScene

import QuestJournal2.GameEvent
import QuestJournal2.PlayerData
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
    HERB_SOURCE
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
    val hintText: String
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
            "Подойди к любой области на карте"
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
            "Подойди к любой области на карте"
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

// ====== События сервер к клиенту ====== //

sealed interface GameEvent {
    val playerId: String
}

data class EnteredArea(
    override val playerId: String,
    val areaId: String
): GameCommand

data class LeftArea(
    override val playerId: String,
    val areaId: String
): GameCommand

data class InteractWithNpc(
    override val playerId: String,
    val npcId: String
): GameCommand

data class InteractedWithHerbSource(
    override val playerId: String,
    val sourceId: String
): GameCommand

data class InventoryChanged(
    override val playerId: String,
    val itemId: String,
    val newCount: Int
): GameCommand

data class QuestStateChanged(
    override val playerId: String,
    val newState: QuestState
): GameCommand

data class NpcMemoryChanged(
    override val playerId: String,
    val memory: NpcMemory
): GameCommand

data class ServerMessage(
    override val playerId: String,
    val text: String
): GameCommand



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
        )
    )

    // Поток событий
    private val _events = MutableSharedFlow<QuestJournal2.GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _commands = MutableSharedFlow<QuestJournal2.GameCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<QuestJournal2.GameCommand> = _commands.asSharedFlow()

    fun trySend(cmd: QuestJournal2.GameCommand): Boolean = _commands.tryEmit(cmd)
    // tryEmit - эт быстрый способ отправить команду (без корутины)

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to initialPlayerState("Oleg"),
            "Stas" to initialPlayerState("Stas")
        )
    )
    val players: StateFlow<Map<String, PlayerState>> = _players.asStateFlow()

    fun start(scope: kotlinx.coroutines.CoroutineScope, questSystem: QuestSystem) {
        // Сервер слушает и выполняет их

        scope.launch {
            commands.collect{ cmd ->
                progressCommand(cmd, questSystem)
            }
        }
    }

    private fun getPlayerData(playerId: String): PlayerState {
        return _players.value[playerId] ?: initialPlayerState(playerId)
    }

    private fun setPlayerData(playerId: String, newdata: PlayerState) {
        val map = _players.value.toMutableMap()
        map[playerId] = newdata
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

    // Метод refreshPlayerArea (playerId: String)
    // должен пересчитывать в какой зоне сейчас находится игрок
    // Получить игрока
    // получить ближайший объект
    // сохрать старое состояние игрока в какой зоне он был ранее
    // рассчитать id зоны в которую он попал теперь new

    // сравнение новой зоны со старой
    // в зависимости от того, в какой зоне он находится в newHint вернуть текст для зоны alchemist и зоны herb_source
    // после обновляем игрока (свойство hintText)
}



























