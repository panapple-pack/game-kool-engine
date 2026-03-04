package lesson6

import com.sun.source.tree.Scope
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

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive




// В настоящей игре очень много событий и процессов, заточенных на времени
// Яд тикает раз в секунду
// Кулдаун способности 1.5 секунд
// Задержка сети 200мс.
// Квест открывает дверь через 10 секунд
// и т.д.

// Если все это будет лежать в onUpdate и таймеры будут обрабатываться вручную - это быстро превратится в кашу

// Корутины это решают
// Позволяют писать время как обычный код: Подождал -> выполнил действие -> подождал -> выполнил действие
// Не замораживают всю игру и UI
// Удобно отменяются или прерываются (например если яд уже был наложен на игрока - отменить корутину и запустить новую с обновленным эффектом яда)

// Основные команды корутин
// launch{ ... } - запуск корутины (включение процесса или запуск таймера)
// delay (ms) - приостанавливает на ограниченное число миллисекунд корутину, но не останавливает ее
// Job + cancel()
// job - контроллер управления корутиной
// сancel() - отмена / остановка корутины (например снять эффект яда)


// delay работает только внутри launch {}
// потому что delay это suspend функция
// suspend fun - функция, которая может приостанавливаться (ждать)
// Обычная функция на это не способна
// suspend функцию можно вызвать только внутри корутины (launch) или внутри другой suspend функции

// Использование scene.coroutineScope
// В Kool есть своя корутинная область
// Когда сцена закрывается - корутины этой сцены тоже автоматически прерываются
// Это просто безопаснее, чем глобальные корутины

// --------- GameState - состояние игрока в UI --------- //
class GameState {
    val playerId = mutableStateOf("Oleg")

    val hp = mutableStateOf(100)
    val maxHp = 100

    val poisonTickLeft = mutableStateOf(0)
    val regenTickLeft = mutableStateOf(0)

    val attackCoolDownMsLeft = mutableStateOf(0L)

    val logLines = mutableStateOf<List<String>>(emptyList())
}

fun pushLog(game: GameState, text: String) {
    game.logLines.value = (game.logLines.value + text).takeLast(20)
}

// -------- EffectManager --------- //

class EffectManager(
    private val game: GameState,
    private val scope: kotlinx.coroutines.CoroutineScope
    // Передаем сюда область корутин, чтобы при выполнении она была привязана к сцене
) {
    private var regenJob: Job? = null
    // Job - это задача корутина, которой мы сможем управлять
    // regenJob - это ссылка на корутину, чтобы мы могли к ней обращаться и управлять ею
    // null по умолчанию потому что корутина по умолчанию не привязана к ссылке (не запущена)
    private var poisonJob: Job? = null

    fun applyPoison(ticks: Int, damagePerTicks: Int, intervalMs: Long) {
        // Метод наложения яда на игрока
        // Если яд уже был наложен - отменяем прошлую корутину
        poisonJob?.cancel()
        // ? - безопасный вызов. Если poisonJob окажется null - cancel() не выполнится

        game.poisonTickLeft.value += ticks
        // Обновляем счетчик тиков сколько еще будет действовать эффект яда
        pushLog(game, "Яд применен на ${game.playerId} длительность ${game.poisonTickLeft} тиков")

        // Запуск новой корутины действия эффекта яда
        poisonJob = scope.launch {
            while (isActive && game.poisonTickLeft.value > 0) {
                // isActive - корутина еще не существует? не была ли отменена
                delay(intervalMs)
                // пауза между нанесением урона действия эффекта яда

                game.poisonTickLeft.value -= 1

                game.hp.value -= (game.hp.value - damagePerTicks).coerceAtLeast(0)

                pushLog(game, "Тик яда: -$damagePerTicks. Осталось Hp: ${game.hp.value}")
            }
            pushLog(game, "Эффект яда завершен")
        }
    }

    fun applyRegen(ticks: Int, healPerTick: Int, intervalMs: Long) {
        regenJob?.cancel()

        game.regenTickLeft.value += ticks
        pushLog(game, "Реген применен на ${game.playerId} длительностью ${game.regenTickLeft} тиков")

        regenJob = scope.launch {
            while (isActive && game.regenTickLeft.value > 0) {
                delay(intervalMs)

                game.regenTickLeft.value += 1
                game.hp.value = (game.hp.value + healPerTick).coerceAtMost(game.maxHp)

                pushLog(game, "Тик регена: +$healPerTick, осталось ${game.hp.value} тиков")
            }
            pushLog(game, "Эффект регена завершен")
        }
    }

    fun cancelPoison() {
        poisonJob?.cancel()
        poisonJob = null
        game.poisonTickLeft.value = 0
        pushLog(game, "Яд снят")
    }

    fun cancelRegen() {
        regenJob?.cancel()
        regenJob = null
        game.regenTickLeft.value = 0
        pushLog(game, "реген снят")
    }
}

class CooldownManager(
    private val game: GameState,
    private val scope: kotlinx.coroutines.CoroutineScope
) {
    private var cooldownJob: Job? = null

    fun startAttackCooldown(totalMs: Long) {
        cooldownJob?.cancel()

        game.attackCoolDownMsLeft.value = totalMs
        pushLog(game, "Кулдаун атаки ${totalMs}мс")

        cooldownJob = scope.launch {
            val step = 100L

            while (isActive && game.attackCoolDownMsLeft.value > 0L) {
                delay(step)
                game.attackCoolDownMsLeft.value = (game.attackCoolDownMsLeft.value - step)
            }
        }
    }

    fun canAttack(): Boolean {
        return game.attackCoolDownMsLeft.value <= 0L
    }
}

fun main() = KoolApplication{
    // Создать экземпляры классов менеджера эффектов и кулдауна
    // делаем кнопки добавления эффекта яда, регена и кулдауна
}




























