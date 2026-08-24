package io.github.some_example_name.old.features.worldeditor

/** Правка карты поверх сгенерированной. */
sealed interface WorldEdit {

    data class Stroke(
        val gridX: Int,
        val gridY: Int,
        /**
         * Параметры кисти запоминаются в самом мазке, а не берутся из текущего состояния:
         * иначе при воспроизведении все мазки лягут той кистью, которая стояла последней.
         */
        val brushSize: Int,
        val erase: Boolean,
        val circle: Boolean
    ) : WorldEdit

    data object Clear : WorldEdit
}

/**
 * Рецепт мира: из чего он получился, а не что получилось.
 *
 * Раньше между экранами ездила уже готовая `Array<BooleanArray>` — 36 КБ производного
 * состояния, по которым нельзя понять, как игрок к ним пришёл. Рецепт весит десятки байт,
 * кладётся в журнал целиком и воспроизводится побайтово: генератор детерминирован по сиду,
 * правки — по своим же параметрам.
 */
data class WorldSpec(
    val gridWidth: Int,
    val gridHeight: Int,
    val seed: String,
    val dayNight: Int,
    val smoothing: Int,
    val edits: List<WorldEdit> = emptyList()
) {
    /** Короткая сводка для журнала. */
    val detail: String
        get() = "seed=$seed dn=$dayNight sm=$smoothing edits=${edits.size}"

    fun buildMap(generator: WorldGenerator = WorldGenerator()): Array<BooleanArray> {
        // Параметры генератора живут в его companion — их читает generateWorld.
        WorldGenerator.GENERATOR_DAY_NIGHT = dayNight
        WorldGenerator.GENERATOR_INTERPOLATE = smoothing

        val map = generator.generateWorld(gridWidth, gridHeight, seed.hashCode().toLong())
        edits.forEach { applyWorldEdit(map, it, gridWidth, gridHeight) }
        return map
    }
}

/**
 * Единственная реализация правки карты. Ею пользуется и редактор в реальном времени,
 * и воспроизведение рецепта — иначе эти два пути со временем разойдутся, и записанный
 * мир перестанет совпадать с тем, который игрок видел на экране.
 *
 * @return менялась ли карта на самом деле.
 */
fun applyWorldEdit(
    map: Array<BooleanArray>,
    edit: WorldEdit,
    gridWidth: Int,
    gridHeight: Int
): Boolean = when (edit) {
    is WorldEdit.Clear -> {
        var changed = false
        for (y in 0 until gridHeight) {
            val row = map[y]
            for (x in 0 until gridWidth) {
                if (row[x]) {
                    row[x] = false
                    changed = true
                }
            }
        }
        changed
    }

    is WorldEdit.Stroke -> {
        val value = !edit.erase
        val radius = edit.brushSize
        val radiusSquared = radius * radius
        var changed = false

        for (dy in -radius..radius) {
            val y = edit.gridY + dy
            if (y !in 0 until gridHeight) continue
            val row = map[y]
            for (dx in -radius..radius) {
                if (edit.circle && dx * dx + dy * dy > radiusSquared) continue
                val x = edit.gridX + dx
                if (x !in 0 until gridWidth) continue
                if (row[x] != value) {
                    row[x] = value
                    changed = true
                }
            }
        }
        changed
    }
}
