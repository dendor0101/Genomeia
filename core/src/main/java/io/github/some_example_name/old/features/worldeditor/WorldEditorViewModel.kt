package io.github.some_example_name.old.features.worldeditor

/**
 * Вся логика редактора мира: генерация карты, кисть, состояние настроек.
 *
 * Ничего не знает ни про scene2d, ни про Pixmap/Texture — оперирует только
 * `map: Array<BooleanArray>`. Благодаря этому её можно дёргать из тестов и из
 * будущего воспроизведения записанных действий игрока, без окна и GL-контекста.
 */
class WorldEditorViewModel(
    private val worldGenerator: WorldGenerator = WorldGenerator(),
    val gridWidth: Int = DEFAULT_GRID_WIDTH,
    val gridHeight: Int = DEFAULT_GRID_HEIGHT
) {
    companion object {
        val DEFAULT_GRID_WIDTH = (128 * 1.5f).toInt()
        val DEFAULT_GRID_HEIGHT = (128 * 1.5f).toInt()

        const val DAY_NIGHT_MIN = 0f
        const val DAY_NIGHT_MAX = 25f
        const val SMOOTHING_MIN = 0f
        const val SMOOTHING_MAX = 50f
        const val BRUSH_SIZE_MIN = 0f
        const val BRUSH_SIZE_MAX = 20f

        private const val SEED_LENGTH = 8
    }

    var seed: String = randomSeed()
        private set

    /**
     * Оба параметра генератора живут в companion WorldGenerator (их читает generateWorld),
     * поэтому здесь они не дублируются, а проксируются — иначе состояние экрана и генератора
     * разъезжается при повторном заходе в редактор.
     */
    val dayNight: Int get() = WorldGenerator.GENERATOR_DAY_NIGHT

    val smoothing: Int get() = WorldGenerator.GENERATOR_INTERPOLATE

    var brushSize: Int = 9
    var isErasing: Boolean = false
    var useCircleBrush: Boolean = true

    var map: Array<BooleanArray> = generate()
        private set

    /**
     * Растёт при каждом изменении карты. Вьюха сравнивает его со своим последним значением
     * и перезаливает текстуру только когда есть что заливать — мазок кистью, не меняющий
     * ни одной ячейки, не стоит ни одной загрузки в GPU.
     */
    var mapVersion: Int = 0
        private set

    fun newSeed() {
        seed = randomSeed()
        regenerate()
    }

    /** Возвращает true, если сид действительно поменялся (и карта перегенерировалась). */
    fun setSeed(value: String): Boolean {
        if (value == seed) return false
        seed = value
        regenerate()
        return true
    }

    fun setDayNight(value: Int) {
        if (value == dayNight) return
        WorldGenerator.GENERATOR_DAY_NIGHT = value
        regenerate()
    }

    fun setSmoothing(value: Int) {
        if (value == smoothing) return
        WorldGenerator.GENERATOR_INTERPOLATE = value
        regenerate()
    }

    fun regenerate() {
        map = generate()
        mapVersion++
    }

    fun clearMap() {
        for (y in 0 until gridHeight) {
            map[y].fill(false)
        }
        mapVersion++
    }

    /**
     * Мазок кистью в клетке (gridX, gridY). Начало координат — левый нижний угол карты.
     */
    fun paint(gridX: Int, gridY: Int) {
        val value = !isErasing
        val radius = brushSize
        val radiusSquared = radius * radius
        var changed = false

        for (dy in -radius..radius) {
            val y = gridY + dy
            if (y !in 0 until gridHeight) continue
            val row = map[y]
            for (dx in -radius..radius) {
                if (useCircleBrush && dx * dx + dy * dy > radiusSquared) continue
                val x = gridX + dx
                if (x !in 0 until gridWidth) continue
                if (row[x] != value) {
                    row[x] = value
                    changed = true
                }
            }
        }

        if (changed) mapVersion++
    }

    private fun generate(): Array<BooleanArray> =
        worldGenerator.generateWorld(gridWidth, gridHeight, seed.hashCode().toLong())

    private fun randomSeed(): String =
        (1..SEED_LENGTH).map { ('0'..'9').random() }.joinToString("")
}
