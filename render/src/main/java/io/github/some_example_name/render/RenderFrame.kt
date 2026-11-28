package io.github.some_example_name.render

import com.badlogic.gdx.math.Matrix4
import io.github.some_example_name.render.pack.DebugAreaIndices
import java.nio.ByteBuffer

/**
 * Всё, что модулю рендера нужно знать про кадр. Полный входной контракт [WorldRenderer].
 *
 * ЗАЧЕМ ОБЪЕКТ, А НЕ СПИСОК АРГУМЕНТОВ
 * ------------------------------------
 * Это граница модуля: глядя на один этот файл, видно ровно то, что рендер получает
 * извне, и видно, что про клетки, организмы и симуляцию здесь нет ничего — только
 * упакованные байты и параметры камеры. Прежняя сигнатура из десяти аргументов такого
 * не показывала, и три из них (isClear, worldX, worldY) годами ехали мёртвым грузом:
 * их передавали оба вызывающих, а тело функции к ним не обращалось ни разу.
 *
 * Экземпляр переиспользуется между кадрами — заводите по одному на вызывающего и
 * заполняйте заново. Аллокаций на кадр здесь быть не должно.
 */
class RenderFrame {

    /** camera.combined. */
    lateinit var cameraProjection: Matrix4

    // ==================== Клетки ====================

    /** Упакованный буфер из CellInstanceBuffer.end(). */
    var cells: ByteBuffer? = null
    /** Группы оптических тел в порядке cells; null — одно тело (редактор/превью). */
    var opticalLinks: io.github.some_example_name.render.pack.OpticalLinks? = null
    var opticalGroups: IntArray? = null
    /** Исходный RGBA генома до отладочной палитры. */
    var opticalColors: IntArray? = null
    /** Тики рождения в порядке cells; -1/null — история неизвестна. */
    var opticalBirthTicks: IntArray? = null
    /** Полные диапазоны тел и их границы из того же снимка; null — обычный обход. */
    var opticalCullRanges: io.github.some_example_name.render.pack.OpticalCullRanges? = null
    var opticalSettings: io.github.some_example_name.render.microscope.MicroscopeSettings? = null

    /**
     * Рамки тел в порядке [cells] для отсечения по камере в отладочном режиме (VisibleCells);
     * null — рисовать все клетки (превью, редактор, режим с заливкой областей: её индексы
     * указывают на места в полном буфере).
     */
    var cellCullRanges: io.github.some_example_name.render.pack.OpticalCullRanges? = null

    /**
     * Неподвижный слой (27.11): террейн и стенка у рамки — инстансы в раскладке [cells], которые
     * за сессию не меняются. Буфер один на мир: пока это тот же объект, на GPU уже лежат его
     * байты (CellGeometry.uploadStatic), снимки мира его не несут. [staticCullRanges] — рамки
     * его кусков для отсечения по камере. null — слоя нет (превью, редактор, микроскоп).
     */
    var staticCells: ByteBuffer? = null
    var staticCullRanges: io.github.some_example_name.render.pack.OpticalCullRanges? = null

    /**
     * Перезалить буфер клеток на GPU.
     *
     * false имеет смысл, когда содержимое буфера с прошлого кадра не менялось: редактор
     * генома стоит на паузе между тиками, а у симуляции экран чаще тиков — между двумя
     * снимками перекладывать те же байты незачем. Если же на GPU с тех пор заливали ДРУГОЙ
     * буфер (превью генома, экран редактора), WorldRenderer перезальёт этот сам: он помнит,
     * чей буфер лежит в VBO.
     */
    var uploadCells: Boolean = true

    /**
     * На какую долю тика кадр рисуется в прошлое от снимка: 0 — ровно снимок, 1 — прошлый
     * тик. Шейдеры клеток и оверлея рисуют каждую клетку между её нынешней позой и позой в
     * прошлом тике (CellInstanceBuffer: «Интерполяция между тиками»).
     *
     * Ноль по умолчанию: у редактора, превью и стенда прошлая поза равна нынешней, им всё равно.
     * Симуляция считает его по времени с публикации снимка (RenderSystem), и между двумя
     * снимками кадры отличаются только этим числом — буфер клеток не перезаливается.
     */
    var interpolationLag: Float = 0f

    // ==================== Оверлей отладки ====================

    /**
     * Фигуры поверх клеток в отладочном режиме — упакованный буфер из
     * OverlayInstanceBuffer.end(): нейросвязи, стрелки направлений, лучи глаз, кольца
     * феромонов. Читается только в [RenderMode.DEBUG]; null — рисовать нечего.
     */
    var overlay: ByteBuffer? = null

    /**
     * Феромоны отладочного режима — буфер из PheromoneInstanceBuffer.end(): x, y,
     * концентрация A и цвет на штуку. null — не рисовать. [pheromoneK] и [pheromoneP] — закон
     * концентрации A / (1 + K r^2) и её порог (PheromonesManager.K/P): по ним шейдер
     * считает размер и прозрачность пятна, а рендер о симуляции не знает.
     */
    var pheromones: ByteBuffer? = null
    var pheromoneK: Float = 0f
    var pheromoneP: Float = 0f

    /**
     * Секунды для анимации оверлея (бегущие по нейросвязям импульсы). По кругу с периодом
     * [TIME_WRAP]: float с ростом теряет дробную часть, и через несколько часов игры
     * импульсы задёргались бы. Шаг на обороте незаметен — см. TIME_WRAP.
     */
    var time: Float = 0f

    /**
     * Как рисовать кадр — см. [RenderMode].
     *
     * Режим приходит с кадром, а не из глобальной настройки: он у каждого экрана свой (у
     * симуляции, у редактора, у меню), а WorldRenderer один на всю игру.
     */
    var mode: RenderMode = RenderMode.DEBUG

    /**
     * Чем закрашивать клетки в отладочном режиме — см. [DebugFilter]. Байт значения у
     * каждой клетки (CellLook) готовит тот, кто собирал буфер, ПОД ЭТОТ ЖЕ фильтр: поэтому
     * фильтр берётся из того же снимка, что и клетки, а не из текущего выбора игрока.
     * Редактор генома его не выставляет — фильтр только у симуляции.
     */
    var debugFilter: DebugFilter = DebugFilter.ORIGINAL

    /** Индексы вершин заливки в буфере клеток этого снимка. */
    var areaIndices: DebugAreaIndices? = null

    /**
     * Отладочный режим без фона: пустота прозрачна, а клетки — с премноженным альфа-каналом
     * (сглаженный край — доля покрытия). Только для снимков в свой буфер
     * (WorldRenderer.renderOffscreen): значок клетки ложится на кнопку интерфейса без квадрата
     * фона мира. Экран мира всегда с фоном.
     */
    var transparentBackground: Boolean = false

    companion object {
        /**
         * Период [time], секунды. Импульсы делают PULSE_HZ = 2 шага в секунду
         * (shaders/debug_view/overlay_common.glsl), поэтому за период — ровно целое число
         * шагов, и на обороте картинка не прыгает. Менять только вместе с PULSE_HZ.
         */
        const val TIME_WRAP = 1000f

        /** Время [time] через [delta] секунд, по кругу. */
        fun advanceTime(time: Float, delta: Float): Float = (time + delta) % TIME_WRAP
    }
}
