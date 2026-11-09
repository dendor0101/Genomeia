package io.github.some_example_name.physics

/**
 * ПОСТОЯННЫЕ МАСШТАБА МИРА. Этап A2 плана (docs/physics/library-integration-plan-2026-10-25.md).
 *
 * [meanLinkLength] — опорный размер: от него пороги, потолки шага и скорости, сон.
 * [meanCellArea] — средний квадрат радиуса клетки: нормировка масс (масса клетки — r²
 * к этой величине, см. buildMasses).
 *
 * До A2 обе выводились из ВСЕГО загруженного набора тел. С телами в рантайме так нельзя:
 * добавленное тело сдвинуло бы средние, и вместе с ними массы и пороги уже живущих тел —
 * рождение одного организма меняло бы физику всех. Поэтому они задаются при создании
 * мира и дальше не меняются: из первой загрузки ([of] — та же арифметика, что была) или
 * явно (игра: размер клетки известен заранее).
 */
class WorldScale(val meanLinkLength: Float, val meanCellArea: Double) {
    companion object {
        /** Средние по набору тел — ровно как считались раньше (BodyDefinition, buildMasses). */
        fun of(definition: BodyDefinition): WorldScale {
            var meanArea = 0.0
            for (i in 0 until definition.count) {
                val r = definition.radius[i].toDouble()
                meanArea += r * r
            }
            meanArea /= definition.count
            return WorldScale(definition.meanLinkLength, meanArea)
        }
    }
}
