package lesson3

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

// Типы предметов
enum class ItemType{
    WEAPON,
    ARMOR,
    POTION
}

// Создание класса с описанием предмета
data class Item(
    val id: String,
    val name: String,
    val type: ItemType,
    val maxStack: Int
)

// Класс, описывающий стак предметов
data class ItemStack(
    val item: Item,
    val count: Int
)

// Создание готовых предметов
val HEALING_POTION = Item(
    "potion_heal",
    "Healing potion",
    ItemType.POTION,
    12
)

val WOOD_SWORD = Item(
    "wood_sword",
    "Wood Sword",
    ItemType.WEAPON,
    1
)

class GameState {
    val playerId = mutableStateOf("Player")
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)

    val potionTicksLeft = mutableStateOf(0)
    val regenTicksLeft = mutableStateOf(0)

    val dummyHp = mutableStateOf(50)

    // Хотбар на 9 слотов, List<ItemStack?> - в ячейку хотбара монжо положить только стак какого - то предмета или null (пусто)
    val hotbar = mutableStateOf(
        List<ItemStack?>(9){null}
        // по умолчанию хотбар заполнен пустыми ячейками и может быть максимум 9 слотов
    )
    // Активный слот инвентаря
    val selectedSlot = mutableStateOf(0)

    // Event log на HUD: список строк.
    // Почему List, а не MutableList? Потому, что мы будем присваивать "новый список"
    // и UI точно увидит изменение.
    val eventLog = mutableStateOf<List<String>>(emptyList())
}

// Интерфейс - договор или набор правил:
// Интерфейс говорит: если ты хочешь быть событием X, у тебя должны быть перечисленные в интерффейсе методы, свойства и поля
// Если в интерфейсе, в котором мы наследуем, лежит свойство или метод - мы должны его реализовать (перезаписать)
// Если ты хочешь иметь паспорт - это договор, по которому у человека должны быть: имя, фамилия, дата рождения, серия и номер
// Интерфейс не говорит нам как делать
// Он не говорит, что должно получиться и что где должно лежать

// Пример Сущность игровая:
// Все игровые сущности обязаны иметь имя, расположение в пространстве (х, у), хп и т.д.

interface Entity {
    val id: String
    val x: Float
    val y: Float
}

class Player(
    override val id: String,
    override var x: Float,
    override var y: Float
) : Entity

sealed interface GameEvent {
    val playerId: String
}

data class ItemAdded(
    override val playerId: String,
    val itemId: String,
    val countAdded: Int,
    val leftOver: Int
) : GameEvent

// Дата класс - класс, который предназначен чаще всего для хранения данных
// Котлин автоматически когда мы помечаем класс "data" - добавляет кучу полезных функций, которые нам бы пришлось писать вручную
// Метод toString() - способ превращать объект в строку для печати и логов
class A(val x: Int)
// println(A(5))
// он выведет обработку класса с данными
// мусор для нас и логов например адрес в памяти A@4f5sd08c
data class B(val x: Int)
// A(x = 5)
// метод equals() - сравнивает одинаковы ли два объекта
// Классы часто сравнивают объекты по их ссылкам
// Дата класс сравнивает по значениям этих объектов
// copy() - создает копию и меняет в копии нужные значения и поля
// Почему важно для UI и состоянии? Мы часто делаем новые копии объектов, чтобы UI увидел изменения
// Не исправляем карточку объекта, а печатаем на интерфейсе новую карточку, но с обновленным числом
// Пакет - пакет данных

data class ItemUsed(
    override val playerId: String,
    val itemId: String
) : GameEvent

data class DamageDealt(
    override val playerId: String,
    val targetId: String,
    val amount: Int
) : GameEvent

data class EffectApplied(
    override val playerId: String,
    val effectedId: String,
    val ticks: Int
) : GameEvent

data class QuestStepCompleted(
    override val playerId: String,
    val questId: String,
    val stepIndex: Int,
) : GameEvent

typealias Listener = (GameEvent) -> Unit

class EventBus{
    // Рассыльщик событий тем, кто на них подписан

    // (GameEvent) -> функция, принимающая GameEvent, возвращает Unit (void) по умолчанию
    private val listeners = mutableListOf<Listener>()    // Список слушателей

    fun subscribe(listener: Listener) {
        listeners.add(listener)
    }

    // Рассылка событий всем подписчикам
    fun publish(event: GameEvent) {
        for (listener in listeners) {
            listener(event)
        }
    }
}

class QuestSystem(
    private val bus: EventBus
) {
    val questId = "q_training"

    // Прогресс конкретного квеста по игрокам
    val progressByPlayer = mutableStateOf<Map<String, Int>>(emptyMap())

    init {
        bus.subscribe { event ->
            handleEvent(event)
        }
    }

    private fun getStep(playerId: String): Int {
        return progressByPlayer.value[playerId] ?: 0
        // если может вернуться null - перехватить и вернуть другое значение
    }

    private fun setStep(playerId: String, step: Int) {
        val newMap = progressByPlayer.value.toMutableMap()
        newMap[playerId] = step
        progressByPlayer.value = newMap.toMap()
    }

    private fun completeStep(playerId: String, stepIndex: Int) {
        setStep(playerId, stepIndex + 1)

        bus.publish(
            QuestStepCompleted(
                playerId,
                questId,
                stepIndex
            )
        )
    }
    private fun handleEvent(event: GameEvent) {
        val player = event.playerId
        val step = getStep(player)

        if (step >= 2) return
        when(event) {
            is ItemAdded -> {
                if (step == 0 && event.itemId == WOOD_SWORD.id) {
                    completeStep(player, 0)
                }
            }
            is DamageDealt -> {
                if (step == 1 && event.targetId == "dummy" && event.amount >= 10) {
                    completeStep(player, 1)
                }
            }
            else -> {}
        }
    }
}

fun putIntoSlot(
    slots: List<ItemStack?>,   // текущие слоты из хотбара
    slotIndex: Int,            // id слота, в который мы кладем
    item: Item,                // сам предмет
    addCount: Int              // количество в стаке
): Pair<List<ItemStack?>, Int> {          // Возвращаем список, но уже с новым предметом
    val newSlots = slots.toMutableList()   // Копия списков слотов для его редактирования
    val current = newSlots[slotIndex]      // текущий стак в слоте (может быть null)

    if (current == null) {
        // Если слот, куда хотим положить, пуст, создаем в нем новый стак
        val count = minOf(addCount, item.maxStack)
        newSlots[slotIndex] = ItemStack(item, count)
        val leftOver = addCount - count
        return Pair(newSlots, leftOver)
    }

    // Если слот в который кладем не пуст - стакаем предметы, только если они того же типа, что уже лежат в слоте
    if (current.item.id == item.id && item.maxStack > 1) {
        val freeSpace = item.maxStack - current.count
        // Отнимаем от кол-ва уже лежащих в стаке предметов от максимально допустимого кол-ва в стаке
        val toAdd = minOf(addCount, freeSpace)
        newSlots[slotIndex] = ItemStack(item, current.count + toAdd)
        val leftOver = addCount - freeSpace
        return Pair(newSlots, leftOver)
    }
    return Pair(newSlots, addCount)
}

fun useSelected(
    slots: List<ItemStack?>,
    slotIndex: Int
): Pair<List<ItemStack?>, ItemStack?> {
    // Пара значений - нужна для того, чтобы:
    // функция могла вернуть два результата сразу, а не один
    // мы сейчас возвращаем два значения, а именно: новый хотбар + информацию о том, сколько предметов еще не влезло в него

    val newSlots = slots.toMutableList()
    val current = newSlots[slotIndex] ?: return Pair(newSlots, null)

    val newCount = current.count - 1

    if (newCount <= 0) {
        // Если стало 0 - значит после использования предмета, стак закончился и слот стал пустым
        newSlots[slotIndex] = null
    } else {
        newSlots[slotIndex] = ItemStack(current.item, newCount)
    }

    return Pair(newSlots, current)
}

fun pushLog(game: GameState, text: String) {
    val old = game.eventLog.value
    val updated = old + text
    game.eventLog.value = updated.takeLast(20)
    // takeLast - обрезает список строк и оставляет только последние n строчек
}

fun main() = KoolApplication {
    val game = GameState()
    val bus = EventBus()
    val quest = QuestSystem(bus)
    bus.subscribe { event ->
        val line = when (event) {
            is ItemAdded -> "Предмет добавлен: ${event.itemId} + ${event.countAdded} (Осталось: ${event.leftOver})"
            is ItemUsed -> "Предмет использован: ${event.itemId}"
            is DamageDealt -> "${event.playerId} нанес ${event.amount} урона ${event.targetId}"
            is EffectApplied -> "Эффект ${event.effectedId} наложен на ${event.ticks} тиков"
            is QuestStepCompleted -> "Шаг ${event.stepIndex + 1} квеста ${event.questId}"
        }

        pushLog(game, "[${event.playerId}] $line")
    }

    addScene {
        defaultOrbitCamera()

        addColorMesh {
            generate { cube{colored()} }

            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0.7f)
                roughness(0.10f)
            }
            onUpdate {
                transform.rotate(45f.deg * Time.deltaT, Vec3f.Z_AXIS)
            }
        }

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, 1f))
            setColor(Color.WHITE, 7f)
        }
        // ЛОГИКА ИГРОВАЯ
        var potionTimerSec = 0f
        // Таймер сколько действует яд

        onUpdate {
            if (game.potionTicksLeft.value > 0) {
                // value - достает именно значение состояния
                potionTimerSec += Time.deltaT
                // Накапливаем секунды действия яда

                if (potionTimerSec >= 1f) {
                    // Прошло >= 1 секунды  -  делаем 1 тик действий
                    potionTimerSec = 0f

                    game.potionTicksLeft.value--
                    // Уменьшаем время действия яда

                    game.hp.value = (game.hp.value).coerceAtLeast(0)
                    // Отнимаем 2 хп за 1 тик действия яда и не допускаем падения HP меньше нуля
                }
            } else {
                potionTimerSec = 0f
                // Если яд закончил свое действие - сбросить таймер
            }
        }
    }
    addScene {
        setupUiScene(ClearColorLoad)
        // ClearColorLoad - у нас уже есть сцена 3Д и она лежит на всем экране,
        // но также у нас теперь есть HUD сцена, и по умолчанию новая сцена перерисовывает старую
        // ClearColorLoad говорит: не перерисовывай сцену прошлую, а наложи слоем поверх

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f,0f,0f,0.5f), 14.dp))
                .padding(12.dp)
            // dp - (density-independent pixel) - условный пиксель, который масштабируется под плотность пикселей на разных экранах
            // то есть в отличии от px интерфейс на dp выглядит одинакового физического размера на разных устройствах

            Column {
                Text("Игрок: ${game.playerId.use()}") {}
                Text("HP: ${game.hp.use()}") {}
                Text("Gold: ${game.gold.use()}") {}
                Text("Действие зелья: ${game.potionTicksLeft.use()}") {}
                Text("Здоровье манекена") {}

                val progress = quest.progressByPlayer.use()[game.playerId.use()]
                val questTest = when(progress) {
                    0 -> "Квест: получите меч"
                    1 -> "Квест: ударьте манекен мечом"
                    else -> "Квест успешно завершен"
                }

                Row {
                    modifier.margin(top = 6.dp)

                    val slots = game.hotbar.use()
                    val selected = game.selectedSlot.use()

                    for (i in 0 until 9) {
                        val isSelected = (i == selected)

                        Box {
                            modifier
                                .size(44.dp, 44.dp)
                                .margin(end = 6.dp)
                                .background(
                                    RoundRectBackground(
                                        if (isSelected) Color(0.2f, 0.2f, 1f, 0.0f) else Color(0f, 0f, 0f, 0.35f),
                                        8.dp
                                    ))
                                .onClick {
                                    game.selectedSlot.value = i
                                }
                            val stack = slots[i]
                            if (stack == null) {
                                Text(" ") {}
                            } else {
                                Column {
                                    modifier.padding(6.dp)

                                    Text("${stack.item.name}") {
                                        modifier.font(sizes.smallText)
                                    }

                                    Text("X${stack.count}") {
                                        modifier.font(sizes.smallText)
                                    }
                                }
                            }
                        }
                    }
                }

                Row {
                    Button("Получить меч") {
                        modifier.margin(end=8.dp).onClick{
                            val pid = game.playerId.value
                            val idx = game.selectedSlot.value

                            val (updated, leftOver) = putIntoSlot(game.hotbar.value, idx, WOOD_SWORD, 6)
                            game.hotbar.value = updated

                            bus.publish(ItemAdded(pid, WOOD_SWORD.id, 1, leftOver))
                        }
                    }
                }
            }
        }
    }
}























