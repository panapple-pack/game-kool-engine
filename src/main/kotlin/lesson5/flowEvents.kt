package lesson5

import de.fabmax.kool.KoolApplication           // Запускает движок
import de.fabmax.kool.addScene                  // Функция добавления сцены (Игра, UI, Меню, Уровень)
import de.fabmax.kool.math.*                    // Превращение числа в градусы (углов)
import de.fabmax.kool.scene.*                   // Сцена, камера по умолчанию, создание фигур, освещение
import de.fabmax.kool.modules.ksl.KslPbrShader  // Шейдеры - материал объекта
import de.fabmax.kool.modules.ksl.KslShader
import de.fabmax.kool.util.Color                // Цветовая палитра (RGBA)
import de.fabmax.kool.util.Time                 // Время - Time.deltaT - сколько секунд пройдет между кадрами
import de.fabmax.kool.pipeline.ClearColorLoad   // Чтобы не стекать элемент уже отрисованный на экране. UI - всегда поверх всего на сцене
import de.fabmax.kool.modules.ui2.*             // HTML - создание текста, кнопок, панелей, Row, Column, mutableStateOf...
import de.fabmax.kool.modules.ui2.UiModifier.*  // CSS - padding()  align()  background()  size()

// -------------------------------
// Импорты: Flow (корутины)
// -------------------------------
import kotlinx.coroutines.launch                    // launch { } - запускаем корутины
import kotlinx.coroutines.flow.MutableSharedFlow    // MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow           // SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow     // MutableStateFlow
import kotlinx.coroutines.flow.StateFlow            // StateFlow
import kotlinx.coroutines.flow.asSharedFlow         // asSharedFlow
import kotlinx.coroutines.flow.asStateFlow          // asStateFlow
import kotlinx.coroutines.flow.collect              // collect

// ------------------------------
// Импорты: Serialization
// ------------------------------
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import lesson3.Player

// -----------------------------
// Импорты
// -----------------------------
import java.io.File
import java.lang.Exception

sealed interface GameEvent {
    val playerId: String
}

data class damageDealt(
    override val playerId: String,
    val targetId: String,
    val amount: Int
): GameEvent

data class questStateChanged(
    override val playerId: String,
    val questId: String,
    val newState: String
): GameEvent

data class PlayerProgressSaved(
    override val playerId: String,
    val reason: String
): GameEvent

// ------------ Серверные данные игрока (те данные, которые мы будем сохранять в JSON файл) ------------- //

// @Serializable - Аннотация или пометка или же указатель на то, что класс под данной аннотацией можно сериализовать
@Serializable
data class PlayerSave(
    val playerId: String,
    val hp: Int,
    val gold: Int,
    val questStates: Map<String, String>
)

// -------------------- ServerWorld с Flow -------------------- //
// Вместо EventBus теперь используем SharedFlow<GameEvent> (наш рассыльщик событий)
// Вместо states - StateFlow - для хранения в себе актуального состояния игрока
//  Это горячие потоки, которые всегда выполняются в корутине

class ServerWorld(initialPlayer: String) {
    private val _events = MutableSharedFlow<GameEvent>(replay = 0)
    // MutableSharedFlow - изменяемый рассыльщик событий, мы можем отправлять события внутрь потока
    // replay - означает *не присылать старые события, новым слушателям*
    // События это почти всегда *что случилось сейчас* они не должны повторяться для новых слушателей
    val event: SharedFlow<GameEvent> = _events.asSharedFlow()
    // asSharedFlow - *получить версию только для чтения*. Через нее публиковать события нельзя, только слушать

    private val _playerState = MutableStateFlow(
        PlayerSave(
            initialPlayer,
            100,
            0,
            mapOf("q_training" to "START")
        )
    )

    val playerState: StateFlow<PlayerSave> = _playerState.asStateFlow()

    // --------  Команды общения клиента с сервером -------- //

    fun dealtDamage(playerId: String, targetId: String, amount: Int) {
        val old = _playerState.value

        val newHp = (old.hp - amount).coerceAtLeast(0)

        _playerState.value = old.copy(hp = newHp)
        // copy - data class функция - создает копию объекта с измененным полем
    }

    fun questStateChanged(playerId: String, questId: String, newState: String) {
        val old = _playerState.value

        // + создает новую Map (cтарую Map не ломаем)
        val newQuestStates = old.questStates + (questId to newState)

        _playerState.value = old.copy(questStates = newQuestStates)
    }

    // suspend функция - функция, которая может ждать (delay/emit)
    suspend fun emitEvent(event: GameEvent) {
        _events.emit(event)
        // emit() - отправить событие всем слушателем
        // emit может подождать, если подписчики медленные (это нормально при управлении потоком)
    }
}


// ------ Сериализация объекта в строку Json ------ //

class  SaveSystem{
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }
    // prettyPrint - делает JSON красивым и читаемым
    // encoreDefaults - записывает значение по умолчанию тоже

    private fun saveFile(playerId: String): File {
        val dir = File("saves")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$playerId.json")
    }

    fun save(player: PlayerSave) {
        val text = json.encodeToString(player)
        // encodeToString превращает наш объект в строку JSON файла

        saveFile(player.playerId).writeText(text)
    }

    fun load(playerId: String): PlayerSave? {
        val file = saveFile(playerId)
        if (!file.exists()) return null

        val text = file.readText()

        return try {
            json.decodeFromString<PlayerSave>(text)
            // Преобразование текста из строки в json в объект playerSave
        } catch (e: Exception) {
            return null
        }
    }
}

class UiState{
    // создать состояние хранящее активного игрока - по умолчанию Олег
    // тоже самое для hp, gold
    // создать состояние хранящее questState - изменяемым состоянием по умолчанию START

    // Состояние logLines изменяемое - хранящая типы данных список только со строками -> по умолчанию пустой список
}




























