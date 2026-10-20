package io.github.some_example_name.old.systems.render

import io.github.some_example_name.old.cells.Bone
import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.cells.Chameleon
import io.github.some_example_name.old.cells.Controller
import io.github.some_example_name.old.cells.Excreta
import io.github.some_example_name.old.cells.Eye
import io.github.some_example_name.old.cells.Fat
import io.github.some_example_name.old.cells.Leaf
import io.github.some_example_name.old.cells.Muscle
import io.github.some_example_name.old.cells.Neuron
import io.github.some_example_name.old.cells.PheromoneEmitter
import io.github.some_example_name.old.cells.PheromoneSensor
import io.github.some_example_name.old.cells.Producer
import io.github.some_example_name.old.cells.Pumper
import io.github.some_example_name.old.cells.Punisher
import io.github.some_example_name.old.cells.SpeedSensor
import io.github.some_example_name.old.cells.Stem
import io.github.some_example_name.old.cells.Sticky
import io.github.some_example_name.old.cells.Sucker
import io.github.some_example_name.old.cells.SuctionCup
import io.github.some_example_name.old.cells.Synapse
import io.github.some_example_name.old.cells.Tail
import io.github.some_example_name.old.cells.TouchTrigger
import io.github.some_example_name.old.cells.Zygote
import io.github.some_example_name.render.pack.CellLook
import io.github.some_example_name.render.pack.OverlayInstanceBuffer

/**
 * Как клетки выглядят в отладочном режиме — одно правило на симуляцию (RenderBufferManager)
 * и редактор генома (EditorRenderSystem), чтобы один и тот же организм в обоих местах
 * выглядел одинаково.
 *
 *  - Цвет клетки — defaultColor её типа, а не цвет из генома: по картинке сразу видно,
 *    где какие клетки. Исключение — хамелеон: он остаётся своего текущего цвета (это и
 *    есть то, что он делает).
 *  - Рисунок — схема клетки (CellLook): хлоропласты у листа, волокна у мышцы, реснички у
 *    присоски. По нему тип узнаётся вблизи, по цвету — издалека.
 *  - Сигнал действия — выход клетки (neuronImpulseOutput): от него анимация рисунка.
 *    Мышца по нему темнеет при сжатии и светлеет при растяжении — шейдером, не здесь.
 *  - Клетки с направлением получают стрелку, глаз — луч до предела видимости, хвост —
 *    жгутик. У выделителя стрелки нет: направление видно по ядру у мембраны.
 *
 * Таблицы строятся по списку клеток один раз: на каждой клетке кадра — только чтение
 * массива по типу, без cellList и без is-проверок.
 */
class DebugCellPalette(private val cellList: List<Cell>) {

    private val colorByType = IntArray(cellList.size) { cellList[it].defaultColor.toIntBits() }

    private val keepsOwnColor = BooleanArray(cellList.size) { cellList[it] is Chameleon }

    /**
     * Рисунок по типу. Клетки модов и выведенные из игры (Breakaway, Fin, FoodSensor,
     * Compass) — без рисунка, плоские: их узнают по цвету.
     */
    private val patternByType = IntArray(cellList.size) { type ->
        when (cellList[type]) {
            is Leaf -> CellLook.CHLOROPLASTS
            is Fat -> CellLook.FAT_DROPLET
            is Bone -> CellLook.OSTEON
            is Tail -> CellLook.FLAGELLUM_BASE
            is Controller -> CellLook.CHIP
            is Muscle -> CellLook.MUSCLE
            is Sucker -> CellLook.CILIA_INWARD
            is Excreta -> CellLook.EXCRETA
            is Punisher -> CellLook.SPIKES
            is Pumper -> CellLook.PUMP_NEEDLES
            is Zygote -> CellLook.ZYGOTE
            is Producer -> CellLook.DIVIDING
            is Neuron -> CellLook.NEURON
            is SuctionCup -> CellLook.SUCTION_RINGS
            is Sticky -> CellLook.STICKY_BUMPS
            is Chameleon -> CellLook.PIGMENTS
            is Eye -> CellLook.EYE
            is TouchTrigger -> CellLook.TOUCH_NET
            is Synapse -> CellLook.SYNAPSE
            is PheromoneEmitter -> CellLook.EMITTER_GRANULES
            is PheromoneSensor -> CellLook.LONG_CILIA
            is SpeedSensor -> CellLook.SHORT_CILIA
            is Stem -> CellLook.STEM
            else -> CellLook.PLAIN
        }
    }

    private val shapeByType = ByteArray(cellList.size) { type ->
        val cell = cellList[type]
        when {
            cell is Eye -> SHAPE_RAY
            cell is Tail -> SHAPE_FLAGELLUM
            cell is Excreta -> SHAPE_NONE
            cell.isDirected -> SHAPE_ARROW
            else -> SHAPE_NONE
        }
    }

    /** defaultColor типа в упаковке Color.toIntBits. */
    fun defaultColor(cellType: Int): Int = colorByType[cellType]

    /**
     * Цвет клетки в отладке. [ownColor] — её собственный цвет (из генома или выставленный
     * хамелеоном).
     */
    fun color(cellType: Int, ownColor: Int): Int =
        if (keepsOwnColor[cellType]) ownColor else colorByType[cellType]

    /** Рисунок типа — CellLook. */
    fun pattern(cellType: Int): Int = patternByType[cellType]

    /**
     * Поле look инстанса: рисунок типа, сигнал действия [action] (выход клетки, −1..1),
     * прозрачность [transparency] (0 — непрозрачная) и байт фильтра отладки [filter]
     * (CellLook.energyFilterByte или signalFilterByte; 0 — без фильтра).
     */
    fun look(cellType: Int, action: Float, transparency: Float = 0f, filter: Int = 0): Int =
        CellLook.look(patternByType[cellType], action, transparency, filter)

    fun isEye(cellType: Int): Boolean = shapeByType[cellType] == SHAPE_RAY

    /** Хвост: вместо стрелки — жгутик, см. [writeFlagellum]. */
    fun hasFlagellum(cellType: Int): Boolean = shapeByType[cellType] == SHAPE_FLAGELLUM

    /** Клетка с направлением, у которой его не показывает рисунок, луч или жгутик. */
    fun hasArrow(cellType: Int): Boolean = shapeByType[cellType] == SHAPE_ARROW

    /**
     * Стрелка направления: из центра на [ARROW_LENGTH] радиусов. [signal] подсвечивает
     * её — клетка действует сильнее, стрелка ярче.
     */
    fun writeArrow(
        sink: OverlaySink,
        x: Float,
        y: Float,
        angleCos: Float,
        angleSin: Float,
        radius: Float,
        signal: Float
    ) {
        val length = radius * ARROW_LENGTH
        sink.add(
            x, y,
            x + angleCos * length, y + angleSin * length,
            ARROW_COLOR,
            OverlayInstanceBuffer.params(OverlayInstanceBuffer.KIND_ARROW, signal, 0)
        )
    }

    /**
     * Жгутик хвоста: прямо от поверхности клетки по её направлению, на [FLAGELLUM_LENGTH]
     * радиусов. [signal] — тяга хвоста, от неё размах волны; [seed] — сдвиг фазы волны,
     * чтобы хвосты не били в такт (индекс частицы — закреплён за клеткой).
     */
    fun writeFlagellum(
        sink: OverlaySink,
        cellType: Int,
        x: Float,
        y: Float,
        angleCos: Float,
        angleSin: Float,
        radius: Float,
        signal: Float,
        seed: Int
    ) {
        val tip = radius * (1f + FLAGELLUM_LENGTH)
        sink.add(
            x + angleCos * radius, y + angleSin * radius,
            x + angleCos * tip, y + angleSin * tip,
            colorByType[cellType],
            OverlayInstanceBuffer.params(OverlayInstanceBuffer.KIND_FLAGELLUM, signal, seed)
        )
    }

    /**
     * Луч глаза: от обода до предела видимости [range] — так его пускает Eye.doOnTick.
     * Цвет — цвет глаза, яркость — от [signal], выхода глаза: видит ли он что-то.
     */
    fun writeEyeRay(
        sink: OverlaySink,
        cellType: Int,
        x: Float,
        y: Float,
        angleCos: Float,
        angleSin: Float,
        radius: Float,
        range: Float,
        signal: Float
    ) {
        if (range <= radius) return
        sink.add(
            x + angleCos * radius, y + angleSin * radius,
            x + angleCos * range, y + angleSin * range,
            colorByType[cellType],
            OverlayInstanceBuffer.params(OverlayInstanceBuffer.KIND_RAY, signal, 0)
        )
    }

    /**
     * Нейросвязь от источника к приёмнику. Цвет — тот, что в NeuralLinkEntity.color, а туда
     * он приходит из генома (LinkData.color), как и в редакторе. [seed] — индекс связи:
     * от него только сдвиг фазы импульсов, чтобы связи не мигали в такт.
     */
    fun writeNeuralLink(
        sink: OverlaySink,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        color: Int,
        signal: Float,
        seed: Int
    ) {
        sink.add(
            fromX, fromY, toX, toY,
            color,
            OverlayInstanceBuffer.params(OverlayInstanceBuffer.KIND_NEURAL, signal, seed)
        )
    }

    /** Куда складывать фигуры оверлея: снимок потока симуляции или буфер редактора. */
    fun interface OverlaySink {
        fun add(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, params: Int)
    }

    companion object {
        private const val SHAPE_NONE: Byte = 0
        private const val SHAPE_ARROW: Byte = 1
        private const val SHAPE_RAY: Byte = 2
        private const val SHAPE_FLAGELLUM: Byte = 3

        /** Длина стрелки направления в радиусах клетки: выходит за клетку на 80 %. */
        const val ARROW_LENGTH = 1.8f

        /** Длина жгутика от поверхности клетки, в её радиусах. */
        const val FLAGELLUM_LENGTH = 2.4f

        /** Белая, чуть прозрачная: читается на клетке любого цвета (обводка у неё тёмная). */
        const val ARROW_COLOR = (235 shl 24) or 0xFFFFFF

        /** Прозрачность «призрака» в редакторе — клетки, которая появится на следующей стадии. */
        const val GHOST_TRANSPARENCY = 0.55f
    }
}
