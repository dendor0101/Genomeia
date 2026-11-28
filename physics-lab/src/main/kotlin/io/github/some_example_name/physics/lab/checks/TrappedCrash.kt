package io.github.some_example_name.physics.lab.checks

import java.util.Locale

/**
 * ПРОНИКНОВЕНИЕ — ЭТО ОШИБКА, А НЕ СОБЫТИЕ. Бросается из решателя, когда центр клетки
 * оказался внутри треугольника чужого тела (см. DemoSession.trapCrash).
 *
 * Несёт не текст, а ЧИСЛА: место воспроизводится тем же посевом, а разбирать надо по
 * величинам — глубину, скорость, радиус, расстояние до ближайшей чужой клетки. Текст
 * нужен только человеку в консоли.
 */
class TrappedCrash(
    val cell: Int,
    val tri: Int,
    /** Складка своей же ткани, а не чужое тело. */
    val fold: Boolean,
    val orgCell: Int,
    val orgTri: Int,
    val x: Double,
    val y: Double,
    /** Скорость клетки в клетках за тик — в тех же единицах, что потолок скорости. */
    val speed: Double,
    /** Радиус контакта клетки в средних длинах связи. */
    val radius: Double,
    val bone: Boolean,
    val free: Boolean,
    /** Насколько глубоко внутрь треугольника зашёл центр, в связях. */
    val depth: Double,
    val nearForeign: Int,
    val nearDist: Double,
    val nearRadius: Double,
    val triVerts: IntArray,
) : RuntimeException() {

    /** Класс случая: по нему случаи группируются, чтобы не разбирать одно и то же дважды. */
    val kind: String
        get() = buildString {
            append(if (fold) "складка" else "чужое тело")
            append(if (bone) ", кость" else "")
            append(if (free) ", свободная" else "")
            append(when {
                speed > 6.0 -> ", на полном ходу"
                speed > 2.0 -> ", быстро"
                else -> ", медленно"
            })
            append(if (depth > 0.25) ", глубоко" else ", у края")
        }

    override val message: String
        get() = String.format(
            Locale.ROOT,
            "клетка #%d (орг %d%s%s, r=%.2f) внутри %s: треугольник %d орг %d [%d %d %d], " +
                "глубина %.3f связи, скорость %.2f кл/тик, ближайшая чужая #%d на %.3f (r=%.2f)",
            cell, orgCell, if (bone) ", кость" else "", if (free) ", свободная" else "",
            radius, if (fold) "СВОЕЙ складки" else "ЧУЖОЙ ткани",
            tri, orgTri, triVerts[0], triVerts[1], triVerts[2],
            depth, speed, nearForeign, nearDist, nearRadius
        )
}
