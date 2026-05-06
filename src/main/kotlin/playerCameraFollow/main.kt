package playerCameraFollow

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
import lesson5.questStateChanged
import org.w3c.dom.Text
import kotlin.math.sqrt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.to

enum class QuestStage {
    NOT_STARTED,
    TALKED_TO_NPC,
    CHEST_OPENED
}

enum class WorldObjectType {
    NPC,
    CHEST
}

enum class NpcTimesTalked {
    FIRST,
    SECOND,
    THIRD
}

data class WorldObject(
    val id: String,
    val label: String,
    val type: WorldObjectType,
    val x: Float,
    val z: Float,
    val interactRadius: Float
)

class GameState {
    val playerX = mutableStateOf(0f)
    val playerZ = mutableStateOf(4f)
    val playerYawDeg = mutableStateOf(180f)
    val moveSpeed = mutableStateOf(3.5f)
    val questStage = mutableStateOf(QuestStage.NOT_STARTED)
    val chestOpened = mutableStateOf(false)
    val gold = mutableStateOf(0)
    val focusObjectId = mutableStateOf<String?>(null)
    val hintText = mutableStateOf("WASD - движение | E - взаимодействие")
    val dialogueText = mutableStateOf("Подойди к фиолетовому кубу-нпс и нажми E")
    val logLines = mutableStateOf(listOf("Сцена загружена"))
    val npcTimesTalked = mutableStateOf(NpcTimesTalked.FIRST)
}

fun pushLog(game: GameState, text: String) {
    game.logLines.value = (game.logLines.value + text).takeLast(10)
}

fun distance2d(ax: Float, az: Float, bx: Float, bz: Float): Float {
    val dx = ax - bx
    val dz = az - bz
    return sqrt(dx * dx + dz * dz)
}

fun lerp(current: Float, target: Float, t: Float): Float {
    // Линейная интерполяция нужна для плавного перемещения объекта от 1 точки к другой
    return current + (target - current) * t
}

fun normalizeOrZero(x: Float, z: Float): Pair<Float, Float> {
    val len = sqrt(x * x + z * z)
    return if (len <= 0.0001f) 0f to 0f else (x / len) to (z / len)
}

fun normalizeAngleDeg(angle: Float): Float {
    // Приводит угол к диапазону значений от 0 до 360 (положительные градусы)
    // -20 -> 340
    // 370 -> 10
    var result = angle
    // рабочая копия угла
    while (result < 0f) result += 360f
    // Если угол отрицательный - поднимем его вверх
    // пока он не попадет в диапазон 0...360
    while (result >= 360) result -= 360f
    // Если угол слишком большой - уменьшаем его
    return result
}

fun shortestAngleDeltaDeg(from: Float, to: Float): Float {
    // Короткая разница между углами
    // Зачем нужно?
    // Камера должна доворачиваться к игроку самым коротким путем, а не пытаться крутиться почти полный круг
    // Например:
    // from = 350    to = 10
    // Понятная разница: 10 - 350 = -340 (но это не кривой не короткий путь)
    // короткий путь это +20 градусов
    var delta = normalizeAngleDeg(to) - normalizeAngleDeg(from)
    // Сначала считаем обычную разницу между нормализованными углами
    if (delta > 180f) delta -= 360f
        // Если разница слишком большая в плюс - значит короче повернуться в другую сторону (в минус)
    if (delta < -180f) delta += 360f
        // Если разница слишком большая в минус - значит короче повернуться в другую сторону (в плюс)

    return delta
}

fun computeYawFromDirection(dirX: Float, dirZ: Float): Float {
    // Если игрок движется в направлении dirX и dirZ - то под каким углом он должен смотреть
    val raw = Math.toDegrees(atan2(dirX.toDouble(), (-dirZ.toDouble()))).toFloat()
    // Функция, которая из направления делает угол
    // toDouble() - преобразовываем в дабл потому что на нем работает атан2
    // -dirZ - пишем минус, потому что на сцене по умолчанию у нас движение вперед это -Z
    // Math.toDegrees() - переведет подсчитанное в градусы
    return if (raw < 0f) raw + 360f else raw
    // atan2 может вернуть отрицательный угол
    // Для удобства - лучше хранить угол в диапазоне 0..360
    // Поэтому -90 станет 270
}

fun isObjectFrontOfPlayer(
    playerX: Float,
    playerZ: Float,
    playerYawDeg: Float,
    obj: WorldObject
): Boolean {
    // Проверка находится ли объект перед игроком
    val yawRad = Math.toRadians(playerYawDeg.toDouble())
    // Угол взгляда игрока в радианах
    // Нужно, потому что sin/cos работают на радианах

    val forwardX = sin(yawRad).toFloat()
    val forwardZ = (-cos(yawRad)).toFloat()

    // На сколько объект смещен от игрока по X и Z
    val toObjX = obj.x - playerX
    val toObjZ = obj.z - playerZ

    val dist = distance2d(playerX, playerZ, obj.x, obj.z)

    // Если объект почти совпал с игроком, считаем его впереди
    if (dist <= 0.0001f) return true

    val dirToObjX = toObjX / dist
    val dirToObjZ = toObjZ / dist

    val dot = forwardX * dirToObjX + forwardZ * dirToObjZ
    // Скалярное произведение
    // Оно показывает на сколько объект совпадает с направлением взгляда игрока
    // dot ~ 1 -> почти прямо перед игроком
    // dot ~ 0 -> сбоку
    // dot < 0 -> сзади

    return dot > 0.45f
    // Если dot достаточно большой - считаем объект впереди
    // 0.45 - это широта конуса, которым мы смотрим
}

fun findFocusedObject(game: GameState, objects: List<WorldObject>): WorldObject? {
    // Ищет объект, который прямо сейчас перед игроком и при этом который доступен для взаимодействия
    val playerX = game.playerX.value
    val playerZ = game.playerZ.value

    val playerYaw = game.playerYawDeg.value
    // Текущий угол на игрока

    val candidates = objects.filter { obj ->
        distance2d(playerX, playerZ, obj.x, obj.z) <= obj.interactRadius &&
                isObjectFrontOfPlayer(playerX, playerZ, playerYaw, obj)
    }
    // Фильтруем все объекты и оставляем только те, которые рядом с игроком и которые перед игроками

    return candidates.minByOrNull { obj ->
        distance2d(playerX, playerZ, obj.x, obj.z)
    }
    // Если таких объектов найдется несколько - то берем ближайший к игроку
}

fun handleInteract(game: GameState, focused: WorldObject?) {
    // Метод логики взаимодействия (Кнопки Е)

    if (focused == null) {
        game.dialogueText.value = "Перед игроком нет объекта для взаимодействия"
        pushLog(game,"Нажали E, но рядом нет объекта")
        return
    }

    when (focused.type) {
        WorldObjectType.NPC -> {
            val npcDialoguesOptions = mapOf(
                "FIRST" to listOf(
                    "[Алхимик]: сначала открой сундук",
                    "[Алхимик]: сначала открой сундук",
                    "[Алхимик]: сначала открой сундук"
                ),
                "SECOND" to listOf(
                    "[Алхимик]: сначала открой сундук",
                    "[Алхимик]: сначала открой сундук",
                    "[Алхимик]: сначала открой сундук"
                ),
                "THIRD" to listOf(
                    "[Алхимик]: сначала открой сундук",
                    "[Алхимик]: сначала открой сундук",
                    "[Алхимик]: сначала открой сундук"
                )

            )
            when(game.questStage.value) {
                QuestStage.NOT_STARTED -> {
                    game.questStage.value = QuestStage.TALKED_TO_NPC
                    // Меняем стадию квеста, что игрок уже поговорил

                    game.dialogueText.value =
                        "[Алхимик]: В сундуке лежит награда. Подойди к сундуку и открой его, там точно не мимик"
                    pushLog(game, "NPC выдал задачу: Открыть сундук")
                }

                QuestStage.TALKED_TO_NPC -> {
                    if (!game.chestOpened.value) {
                        when(game.npcTimesTalked.value) {
                            NpcTimesTalked.FIRST -> {
                                game.dialogueText.value =
                                    npcDialoguesOptions["FIRST"].
                                pushLog(game, "NPC напомнил про сундук")
                            }
                        }

                    } else {
                        game.dialogueText.value =
                            "[Алхимик]: Победа, ты справился и получил награду, теперь иди отсюда"
                        pushLog(game, "NPC подтвердил выполнение квеста")
                    }
                }

                QuestStage.CHEST_OPENED -> {
                    game.dialogueText.value =
                        "[Алхимик]: Все уже готово, ты уже взял награду"
                }
            }
        }
        WorldObjectType.CHEST -> {
            if (game.questStage.value == QuestStage.NOT_STARTED) {
                game.dialogueText.value = "Сундук закрыт, возьми ключ у Алхимика"
                pushLog(game, "Попытка открыть сундук без ключа")
                return
            }
            if (game.chestOpened.value) {
                game.dialogueText.value == "Сундук уже открыть"
                pushLog(game, "Попытка открыть уже открытый сундук")
                return
            }

            game.chestOpened.value = true

            game.questStage.value = QuestStage.CHEST_OPENED

            game.gold.value += 20

            game.dialogueText.value = "Ты открыл сундук и получил 20 золота"
            pushLog(game, "Сундук открыт, выдана награда в 20 золота")
        }
    }
}


































