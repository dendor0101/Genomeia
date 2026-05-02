# Документация Genomeia — Биологический симулятор эволюции

## Содержание

1. [Обзор проекта](#обзор-проекта)
2. [Архитектура](#архитектура)
3. [Основные компоненты](#основные-компоненты)
4. [Система клеток](#система-клеток)
5. [Геном и наследственность](#геном-и-наследственность)
6. [Физическая система](#физическая-система)
7. [Нейронная сеть](#нейронная-сеть)
8. [Редактор геномов](#редактор-геномов)
9. [Система рендеринга](#система-рендеринга)
10. [Пользовательский интерфейс](#пользовательский-интерфейс)
11. [Сборка и запуск](#сборка-и-запуск)
12. [Расширение функциональности](#расширение-функциональности)

---

## Обзор проекта

**Genomeia** — это кроссплатформенный биологический симулятор эволюции, написанный на языке **Kotlin** с использованием фреймворка **libGDX**. Проект позволяет пользователям создавать живые организмы через проектирование генома, наблюдать за их взаимодействием, выживанием и эволюцией в симулированной среде.

### Ключевые возможности

- **Конструктор организмов**: Создание существ с уникальными поведениями через редактирование генома
- **Эволюционная симуляция**: Организмы взаимодействуют, конкурируют за ресурсы и эволюционируют
- **Многопоточная физика**: Оптимизированная симуляция с использованием пространственного хеширования
- **Нейронные сети**: Клетки могут быть соединены нейронными связями для сложного поведения
- **Кроссплатформенность**: Поддержка Desktop (LWJGL3), Android и iOS

### Технологический стек

- **Язык**: Kotlin
- **Фреймворк**: libGDX
- **UI библиотека**: VisUI
- **Сборка**: Gradle
- **Структура данных**: fastutil (оптимизированные коллекции)

---

## Архитектура

Проект использует **компонентно-ориентированную архитектуру (ECS-like)** с чётким разделением ответственности между системами.

### Структура пакетов

```
io.github.some_example_name.old/
├── cells/                    # Типы клеток и их поведение
├── commands/                 # Система команд для управления миром
├── core/                     # Ядро: DI, настройки, утилиты
├── editor/                   # Редактор геномов
├── entities/                 # Компоненты сущностей (ECS)
├── systems/                  # Системы обработки
│   ├── genomics/            # Геномика и развитие клеток
│   ├── physics/             # Физика и коллизии
│   ├── render/              # Рендеринг
│   └── simulation/          # Главный цикл симуляции
└── ui/                       # Пользовательский интерфейс
    ├── dialogs/             # Диалоговые окна
    └── screens/             # Экраны приложения
```

### Dependency Injection (DI)

Проект использует простую систему внедрения зависимостей через контейнеры:

- **DIGameGlobalContainer**: Глобальные зависимости игры
- **DISimulationContainer**: Зависимости симуляции
- **DIGenomeEditorContainer**: Зависимости редактора геномов
- **DIContext**: Контекст, передаваемый в клетки для доступа к системам

---

## Основные компоненты

### Entity System

Базовый класс `Entity` управляет пулами объектов для оптимизации производительности:

```kotlin
abstract class Entity(startMaxAmount: Int) {
    protected var maxAmount = startMaxAmount
    var lastId = -1
    var deadStack = IntArrayList(startMaxAmount)  // Пул мёртвых индексов
    var isAlive = BooleanArray(maxAmount)
    var aliveList = IntArrayList(startMaxAmount)  // Список активных сущностей
    
    protected fun add(): Int  // Добавление сущности
    protected fun delete(index: Int)  // Удаление сущности
    fun clear()  // Очистка всех сущностей
    fun resize()  // Динамическое увеличение пула
}
```

**Принцип работы**:
- Используется паттерн "Object Pool" для избежания аллокаций
- Мёртвые индексы возвращаются в `deadStack` для повторного использования
- `aliveList` содержит только активные сущности для быстрой итерации

### Типы сущностей

| Сущность | Описание |
|----------|----------|
| `ParticleEntity` | Базовые физические частицы (позиция, скорость, радиус) |
| `CellEntity` | Клетки организмов (тип, энергия, нейронные импульсы) |
| `LinkEntity` | Связи между клетками (физические и нейронные) |
| `OrganEntity` | Органы — группы специализированных клеток |
| `SubstancesEntity` | Питательные вещества и ресурсы среды |
| `PheromoneEntity` | Феромоны для химической коммуникации |
| `SpecialEntity` | Специфичные данные для особых клеток (глаза, хвосты) |

---

## Система клеток

### Базовый класс Cell

Все типы клеток наследуются от абстрактного класса `Cell`:

```kotlin
sealed class Cell(
    val defaultColor: Color,        // Цвет по умолчанию
    val cellTypeId: Int,            // ID типа клетки
    val isDirected: Boolean,        // Имеет направление
    val isNeural: Boolean,          // Поддерживает нейроны
    val maxEnergy: Float,           // Максимальная энергия
    val isNeuronTransportable: Boolean,
    val effectOnContact: Boolean,   // Эффект при контакте
    val isCollidable: Boolean,      // Участвует в коллизиях
    val descriptionBundle: String?, // Ключ локализации описания
    val specialData: KClass<out SpecialModData>
) {
    open fun onStart(cellIndex: Int, threadId: Int)
    open fun doOnTick(cellIndex: Int, threadId: Int)
    open fun onContact(cellIndex: Int, particleIndexCollided: Int, distance: Float, threadId: Int)
    open fun onDie(cellIndex: Int)
    open fun onLinkDeleted(cellIndex: Int, linkIndex: Int, threadId: Int)
}
```

### Типы клеток

#### Структурные клетки

| Клетка | Описание |
|--------|----------|
| **Bone** | Базовая структурная клетка, обеспечивает жёсткость |
| **Muscle** | Сокращающаяся клетка, создаёт движение |
| **Tail** | Хвостовая клетка для плавания |
| **Sticky** | Липкая клетка для прикрепления к поверхностям |
| **SuctionCup** | Присоска для фиксации |

#### Сенсорные клетки

| Клетка | Описание |
|--------|----------|
| **Eye** | Детектор цвета и объектов в поле зрения |
| **Sensor** | Базовый сенсор расстояния |
| **Compass** | Определяет направление (синус угла) |
| **PheromoneSensor** | Обнаружение феромонов |
| **TouchTrigger** | Реагирует на прикосновения |

#### Метаболические клетки

| Клетка | Описание |
|--------|----------|
| **Leaf** | Фотосинтез, производство энергии из света |
| **Producer** | Производство веществ |
| **Fat** | Накопление энергии |
| **Excreta** | Выделение отходов |
| **Sucker** | Поглощение питательных веществ |

#### Нейронные клетки

| Клетка | Описание |
|--------|----------|
| **Neuron** | Базовый нейрон, передаёт импульсы |
| **Controller** | Управление пользователем (клавиатура/тач) |
| **Chameleon** | Меняет цвет на основе нейронных сигналов |
| **Breakaway** | Отмирает при получении импульса (апоптоз) |
| **Pumper** | Перекачка веществ |
| **Vascular** | Транспортная система |

#### Специальные клетки

| Клетка | Описание |
|--------|----------|
| **Zygote** | Зигота — начальная клетка организма |
| **Punisher** | Атакующая клетка |
| **Mike** | (Специфичная функция) |
| **PheromoneEmitter** | Выделение феромонов |

### Жизненный цикл клетки

1. **onStart**: Инициализация при создании
2. **doOnTick**: Обновление каждый тик симуляции
3. **onContact**: Обработка столкновений с другими частицами
4. **onDie**: Очистка при смерти
5. **onLinkDeleted**: Реакция на удаление связи

---

## Геном и наследственность

### Структура генома

Геном определяет развитие организма через стадии:

```kotlin
class Genome(
    val name: String,                              // Название генома
    val genomeStageInstruction: MutableList<GenomeStage>,  // Стадии развития
    val dividedTimes: IntArray,                    // Счётчик делений
    val mutatedTimes: IntArray                     // Счётчик мутаций
)

class GenomeStage(
    val cellActions: HashMap<Int, CellAction>  // Действия для каждой клетки
)

data class CellAction(
    var divide: Action?,   // Инструкция деления
    var mutate: Action?    // Инструкция мутации
)

data class Action(
    val id: Int,                      // ID новой клетки
    var angle: Float?,                // Угол деления
    var cellType: Int?,               // Тип новой клетки
    val physicalLink: HashMap<Int, LinkData?>,  // Физические связи
    var color: Color?,                // Цвет
    val angleDirected: Float?,        // Направленный угол
    val funActivation: Int?,          // Функция активации нейрона
    val a: Float?, val b: Float?, val c: Float?,  // Параметры функции
    val isSum: Boolean?,              // Суммировать входы
    val colorRecognition: Int?,       // Распознавание цвета
    val lengthDirected: Float?        // Направленная длина
)

data class LinkData(
    val length: Float?,          // Длина связи
    val isNeuronal: Boolean,     // Нейронная связь
    val weight: Float?,          // Вес нейронной связи
    val directedNeuronLink: Int?,// Направленность
    val isExtra: Boolean         // Дополнительная связь
)
```

### Процесс деления клеток

1. Клетка достигает порога энергии
2. Считывается инструкция `divide` из текущей стадии генома
3. Создаётся новая клетка с указанным `cellType`
4. Устанавливается связь (`physicalLink`) с родительской клеткой
5. Переход к следующей стадии или повторение текущей

### Мутации

Мутации происходят через инструкцию `mutate`:
- Изменение типа клетки
- Добавление/удаление связей
- Изменение параметров (угол, цвет)
- Вероятность мутации зависит от настроек симуляции

### Формат хранения

Геномы хранятся в JSON-файлах в папке `assets/genomes/`:

**Примеры пресетов**:
- `Burdock leaf.json` — лист лопуха
- `Cactus.json` — кактус
- `Caterpillar.json` — гусеница
- `Fish.json` — рыба
- `Megalodon.json` — мегалодон
- `Tardigrade.json` — тихоходка
- `Snail.json` — улитка
- `Star.json` — звезда

---

## Физическая система

### GridManager — Пространственное хеширование

Для оптимизации проверки коллизий используется сетка:

```kotlin
class GridManager(
    var gridWidth: Int,
    var gridHeight: Int,
    val maxAmountOfParticles: Int
) {
    var grid = IntArray(gridSize * maxAmountOfParticles) { -1 }
    var particleCounts = IntArray(gridSize)
    var mapMoreThenMax = Array(...)  // Для ячеек с превышением лимита
    
    fun addParticle(x: Int, y: Int, value: Int): Int
    fun removeParticle(cellIndex: Int, value: Int): Boolean
    fun getParticles(x: Int, y: Int): IntArray
}
```

**Оптимизации**:
- Основная сетка хранит до `maxAmountOfParticles` в ячейке
- При переполнении используется `Int2ObjectOpenHashMap`
- Разбиение на чанки для многопоточной обработки

### ParticlePhysicsSystem

Обрабатывает физику частиц:
- Движение по инерции
- Столкновения (упругие/неупругие)
- Трение о среду
- Гравитация (опционально)

### LinkPhysicsSystem

Управляет связями между клетками:
- **Физические связи**: Пружины с заданной длиной
- **Нейронные связи**: Передача импульсов без физического воздействия
- **Направленные связи**: Однонаправленная передача

**Алгоритм**:
```kotlin
fun iterateLinks() {
    // 1. Вычисление сил пружины для каждой связи
    // 2. Применение сил к связанным клеткам
    // 3. Обновление позиций
    // 4. Проверка на разрыв связей
}
```

### Многопоточность

Симуляция распараллелена через `ThreadManager`:

```kotlin
class ThreadManager {
    val executor = Executors.newFixedThreadPool(threadCount)
    val futures = mutableListOf<Future<*>>()
    
    fun runChunkStage(isOdd: Boolean, block: (Int, Int, Int) -> Unit) {
        // Разбиение мира на чанки
        // Параллельное выполнение для каждого чанка
    }
}
```

**Стратегия**:
- Мир делится на чанки размером `chunkSize`
- Каждый поток обрабатывает свой набор чанков
- Синхронизация через `Future.get()` между этапами
- Чередование чётных/нечётных чанков для избежания гонок

---

## Нейронная сеть

### Архитектура

Организмы имеют распределённую нейронную сеть:
- **Узлы**: Клетки типа Neuron, Sensor, Controller
- **Связи**: Directed/undirected links с весами
- **Импульсы**: Активация распространяется по сети

### Функции активации

Поддерживаемые функции (через `funActivation`):

1. **Линейная**: `f(x) = x`
2. **Сигмоида**: `f(x) = 1 / (1 + e^(-x))`
3. **ReLU**: `f(x) = max(0, x)`
4. **Пороговая**: `f(x) = x > threshold ? 1 : 0`
5. **Периодическая**: `f(x) = sin(a*x + b) + c`

Параметры задаются через `a`, `b`, `c` в `Action`.

###Propagation импульсов

```kotlin
// В CellEntity
var neuronImpulseOutput = FloatArray(maxAmount)  // Выходной импульс клетки
var neuronImpulseInput = FloatArray(maxAmount)   // Входной импульс

// Вычисление в doOnTick
val inputSum = if (isSum) sumAllInputs() else maxInput()
val activated = activationFunction(inputSum, params)
neuronImpulseOutput[cellIndex] = activated
```

### Специализированные нейроклетки

**Eye**: 
- Использует алгоритм DDA (Digital Differential Analyzer) для трассировки луча
- Определяет цвет объекта в направлении взгляда
- Возвращает нормализованный сигнал (0..1)

**Compass**:
- Возвращает `sin(angle)` клетки
- Полезно для ориентации в пространстве

**Controller**:
- Связывается с вводом пользователя
- Генерирует импульс при нажатии клавиши

**Chameleon**:
- Принимает 3 входных сигнала (R, G, B)
- Меняет цвет клетки соответственно

---

## Редактор геномов

### Компоненты редактора

Расположен в пакете `io.github.some_example_name.old.editor/`:

```
editor/
├── commands/           # Команды редактирования
│   ├── AddNeuralLinkCommand.kt
│   ├── DivideCellCommand.kt
│   ├── MoveCellCommand.kt
│   ├── RemoveCellCommand.kt
│   └── MutateCellCommand.kt
├── entities/           # Replay-сущности для истории
│   ├── CellReplay.kt
│   └── LinkReplay.kt
├── system/             # Логика и рендеринг редактора
│   ├── EditorLogicSystem.kt
│   ├── EditorRenderSystem.kt
│   └── EditorSimulationSystem.kt
└── ui/                 # UI редактора
    ├── GenomeEditorScreen.kt
    ├── SaveGenomeDialog.kt
    └── buildEditorMenu.kt
```

### Команды редактирования

Все команды реализуют паттерн **Command** для поддержки undo/redo:

```kotlin
interface EditorCommand {
    fun execute()
    fun undo()
}

class DivideCellCommand(
    val cellIndex: Int,
    val angle: Float,
    val cellType: Int
) : EditorCommand {
    override fun execute() { /* Добавить новую клетку */ }
    override fun undo() { /* Удалить новую клетку */ }
}
```

### Интерфейс редактора

**GenomeEditorScreen** предоставляет:
- Визуализацию текущего организма
- Панель инструментов для добавления клеток
- Инспектор свойств выбранной клетки
- Таймлайн стадий развития
- Кнопки сохранения/загрузки генома

---

## Система рендеринга

### RenderSystem

Основная система отрисовки:

```kotlin
class RenderSystem(
    val particleEntity: ParticleEntity,
    val cellEntity: CellEntity,
    val linkEntity: LinkEntity,
    val shaderManager: ShaderManager,
    val renderBufferManager: RenderBufferManager
) {
    fun render() {
        // 1. Очистка буфера
        // 2. Отрисовка частиц
        // 3. Отрисовка связей
        // 4. Пост-эффекты через шейдеры
    }
}
```

### ShaderManager

Управление шейдерами для эффектов:
- **Bloom**: Свечение ярких объектов
- **Color correction**: Коррекция цвета
- **Distortion**: Искажение воды
- **MSAA**: Сглаживание (настраиваемое)

```kotlin
class ShaderManager {
    fun loadShader(name: String, vertexPath: String, fragmentPath: String)
    fun bindShader(name: String)
    fun setUniform(name: String, value: Float)
}
```

### RenderBufferManager

Двойная буферизация для плавности:
- Один буфер читается во время рендеринга
- Второй записывается во время симуляции
- Переключение между кадрами

---

## Пользовательский интерфейс

### Экраны приложения

Расположены в `io.github.some_example_name.old.ui.screens/`:

| Экран | Описание |
|-------|----------|
| **MenuScreen** | Главное меню, выбор режима игры |
| **SimulationScreen** | Основной экран симуляции |
| **WorldEditorScreen** | Редактор мира (размещение организмов) |
| **GenomeEditorScreen** | Редактор геномов |
| **SettingsScreen** | Настройки игры |
| **JsonSettingsEditorScreen** | Продвинутые настройки (JSON) |

### UI библиотека

Используется **VisUI** (расширение для libGDX):
- **VisTextButton**: Стильные кнопки
- **VisSlider**: Ползунки настроек
- **VisSelectBox**: Выпадающие списки
- **VisCheckBox**: Чекбоксы
- **ScrollableTextArea**: Прокручиваемый текст

### Локализация

Поддержка многоязычности через `bundle`:
- Русский (RU)
- Английский (EN)

```kotlin
val description = descriptionBundle?.let { bundle.get(descriptionBundle) } ?: ""
```

### Адаптивность

UI масштабируется под плотность экрана:

```kotlin
val density = Gdx.graphics.density
val desiredSize = (baseSize * density).toInt()
font.data.setScale(desiredSize.toFloat() / MIN_GEN_SIZE.toFloat())
```

---

## Сборка и запуск

### Требования

- **JDK 11+**
- **Android SDK** (для Android сборки)
- **Gradle 7+**

### Структура модулей

```
/workspace/
├── core/          # Общий код (Kotlin)
├── lwjgl3/        # Desktop версия
├── android/       # Android версия
├── ios/           # iOS версия
├── assets/        # Ресурсы (текстуры, звуки, геномы)
└── build.gradle   # Конфигурация сборки
```

### Сборка

**Desktop (LWJGL3)**:
```bash
./gradlew lwjgl3:run          # Запуск
./gradlew lwjgl3:jar          # Сборка JAR
./gradlew lwjgl3:distZip      # Дистрибутив
```

**Android**:
```bash
./gradlew android:assembleDebug    # Debug APK
./gradlew android:assembleRelease  # Release APK
```

**iOS** (требуется macOS):
```bash
./gradlew ios:launchIOSDevice
./gradlew ios:launchIOSSimulator
```

### Конфигурация

Основные настройки в `GlobalSettings.kt`:

```kotlin
object GlobalSettings {
    var MSAA = 2                    // Уровень сглаживания
    var MUSIC_VOLUME = 50           // Громкость музыки (%)
    var UI_SCALE = 1.0f             // Масштаб интерфейса
    var THREAD_COUNT = 4            // Количество потоков симуляции
}
```

---

## Расширение функциональности

### Добавление нового типа клетки

1. Создать класс в `cells/`:

```kotlin
class NewCell(cellTypeId: Int) : Cell(
    defaultColor = Color.WHITE,
    cellTypeId = cellTypeId,
    isNeural = true,
    descriptionBundle = "cell.newcell.description"
) {
    override fun doOnTick(cellIndex: Int, threadId: Int) {
        // Логика клетки
        with(cellEntity) {
            energy[cellIndex] -= substrateSettings.cellsSettings[
                cellType[cellIndex].toInt()
            ].energyActionCost
        }
    }
}
```

2. Зарегистрировать в `CellSystem.kt`
3. Добавить текстуру в `assets/`
4. Добавить строки локализации

### Добавление новой команды редактора

```kotlin
class NewEditorCommand : EditorCommand {
    override fun execute() { /* ... */ }
    override fun undo() { /* ... */ }
}
```

Зарегистрировать в `EditorLogicSystem.kt`.

### Изменение физических параметров

Настройки в `SubstrateSettings.kt`:

```kotlin
class SubstrateSettings {
    val cellsSettings = Array<CellSettings>(...)
    val data = SimulationDataSettings()
    val physics = PhysicsSettings()
}
```

### Добавление шейдера

1. Создать `.vert` и `.frag` файлы в `assets/shaders/`
2. Загрузить через `ShaderManager.loadShader()`
3. Применить в `RenderSystem.render()`

---

## Производительность и оптимизации

### Используемые техники

1. **Object Pooling**: Переиспользование индексов сущностей
2. **Spatial Hashing**: GridManager для быстрых коллизий
3. **Multithreading**: Параллельная обработка чанков
4. **Primitive Collections**: fastutil вместо стандартных коллекций
5. **Double Buffering**: Разделение логики и рендеринга
6. **Batch Rendering**: Пакетная отрисовка спрайтов

### Профилирование

Рекомендуемые метрики:
- FPS (цель: 60)
- Время тика симуляции (<16ms)
- Количество активных сущностей
- Использование памяти

---

## Известные ограничения

1. **Максимум частиц**: Ограничен размером сетки и доступной памятью
2. **Сложность нейросетей**: Большое количество нейронов снижает производительность
3. **Мобильные устройства**: Ограниченное количество потоков и памяти
4. **Сохранения**: Нет системы автосохранения состояния симуляции

---

## Сообщество и ресурсы

- **TikTok**: [@genomeia](https://www.tiktok.com/@genomeia)
- **Telegram (RU)**: [t.me/genomeia](https://t.me/genomeia)
- **Discord (EN)**: [discord.gg/HRajjtbENs](https://discord.com/invite/HRajjtbENs)
- **YouTube**: [@Genomeia-project](https://www.youtube.com/@Genomeia-project)

---

## Лицензия

Проект распространяется под лицензией, указанной в файле `LICENSE`.

---

*Документация актуальна для версии проекта на момент анализа. Некоторые детали реализации могут измениться в будущих версиях.*
