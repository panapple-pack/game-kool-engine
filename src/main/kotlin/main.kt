import de.fabmax.kool.KoolApplication           // Запускает движок
import de.fabmax.kool.addScene                  // Функция добавления сцены (Игра, UI, Меню, Уровень)

import de.fabmax.kool.math.Vec3f                // 3D вектор (x, y, z)
import de.fabmax.kool.math.deg                  // Превращение числа в градусы (углов)
import de.fabmax.kool.scene.*                   // Сцена, камера по умолчанию, создание фигур, освещение

import de.fabmax.kool.modules.ksl.KslPbrShader  // Шейдеры - материал объекта
import de.fabmax.kool.util.Color                // Цветовая палитра (RGBA)
import de.fabmax.kool.util.Time                 // Время - Time.deltaT - сколько секунд пройдет между кадрами

import de.fabmax.kool.pipeline.ClearColorLoad   // Чтобы не стекать элемент уже отрисованный на экране. UI - всегда поверх всего на сцене

import de.fabmax.kool.modules.ui2.*             // HTML - создание текста, кнопок, панелей, Row, Column, mutableStateOf...
import de.fabmax.kool.modules.ui2.UiModifier.*  // CSS - padding()  align()  background()  size()

class GameState {
    val playerId = mutableStateOf("Player")
    // mutableStateOf - создает состояние, за которым будет следить UI элементы
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)
    val potionTicksLeft = mutableStateOf(0)
    // Тики - условная единица времени, на которую мы опираемся, чтобы не зависить от клиентского FPS
    // На нашем примере простом 1 тик будет равен 1 секунде (объем тика определяется разработчиком игры самостоятельно)
}

// KoolApplication - указывает, что запускаемое приложение - это приложение, написанное на KOOL
fun main() = KoolApplication {
    // Запуск движка
    val game = GameState()

    // 1 СЦЕНА - ИГРОВОЙ 3D МИР
    addScene {
        defaultOrbitCamera()
        // Готовая камера - по умолчанию крутится на пкм вокруг точки сцены

        addColorMesh {
            generate {     // генерация геометрии в сцене
                cube {     // сгенерировать пресет в виде куба
                    colored()
                    // Добавляем цвет в стороны куба
                }
            }
            shader = KslPbrShader {
                // Назначаем материал фигуре
                color { vertexColor() }
                // Берем подготовленные цвета из сторон куба
                metallic(0f)       // Металлизация (0f - пластик, 1f - отполированный кусок металла)
                roughness(0.25f)   // Шероховатость (0f - глянец, 1f - матовый)
            }
            onUpdate {  // Метод, вызывается каждый кадр
                transform.rotate(45f.deg * Time.deltaT, Vec3f.X_AXIS)
                // rotate(угол, ось)
                // 45 - градусы в секунду
                // * deltaT - сколько прошло секунд между кадрами и насколько уже повернулся куб
            }
        }
        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, 0f, 0f))
            // Установили в позицию немного дальше то центра где лежит куб
            setColor(Color.WHITE, 5f)
            // Включить белый свет с яркостью 5 кельвинов
        }
        // ЛОГИКА ИГРОВАЯ
        var potionTimerSec = 0f
        // Таймер сколько действует яд
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
    addScene {
    // Сцена для худа
        setupUiScene(ClearColorLoad)
        // Режим сцены в фиксированный UI

        addPanelSurface {
            // Создать панель интерфейса (div)
            modifier
                .size(360.dp, 210.dp)
                .align(AlignmentX.Start, AlignmentY.Top)
                .padding(16.dp)
                // Приклеиваем интерфейс к левой верхней части экрана
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.5f), 14.dp))

            Column {
                Text("Игрок: ${game.playerId.use()}") {}
                Text("HP: ${game.hp.use()}") {}
                Text("Gold: ${game.gold.use()}") {}
                Text("Действие зелья: ${game.potionTicksLeft.use()}") {}
            }
        }
    }
}