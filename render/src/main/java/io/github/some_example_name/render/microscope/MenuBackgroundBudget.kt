package io.github.some_example_name.render.microscope

import io.github.some_example_name.render.RenderMode

/** Только выбор оформления меню; не участвует в тиках мира или настройках игрока. */
class MenuBackgroundBudget(forceDebug: Boolean = false) {
    val mode get() = if (useDebug) RenderMode.DEBUG else RenderMode.TISSUE_MAP
    var useDebug = forceDebug
        private set
    var medianMillis = 0.0
        private set
    var decided = forceDebug
        private set
    private val samples = DoubleArray(7)
    private var count = 0
    private var frames = 0
    private var pixels = 0L

    /** Новый размер требует новой проверки; принятое снижение качества сохраняем до перезапуска. */
    fun beginVisit() { frames = 0 }

    fun shouldMeasure(width: Int, height: Int, ready: Boolean): Boolean {
        if (useDebug || !ready || width <= 0 || height <= 0) return false
        val area = width.toLong() * height
        if (pixels != area) {
            pixels = area
            count = 0
            frames = 0
            decided = false
        }
        if (decided) return false
        frames++
        // Прогреть программы, атласы и драйвер. Не включать первую загрузку в оценку GPU.
        return frames >= 90 && (frames - 90) % 12 == 0
    }

    fun record(millis: Double) {
        if (decided || !millis.isFinite() || millis < 0.0) return
        samples[count++] = millis
        if (count == samples.size) {
            samples.sort()
            medianMillis = samples[samples.size / 2]
            // Бюджет фона оставляет запас для UI и симуляции при 60 Гц.
            useDebug = medianMillis > 6.0
            decided = true
        }
    }
}
