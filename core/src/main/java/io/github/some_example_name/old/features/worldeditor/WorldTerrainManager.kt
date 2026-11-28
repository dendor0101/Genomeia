package io.github.some_example_name.old.features.worldeditor

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.SubstancesEntity
import io.github.some_example_name.old.physics.bridge.StaticTerrain
import kotlin.Float
import kotlin.random.Random

class WorldTerrainManager(
    val particleEntity: ParticleEntity,
    val substancesEntity: SubstancesEntity
) {
    var map: Array<BooleanArray>? = null
    val gridWidth: Float = 0f
    val gridHeight: Float = 0f

    fun getColor(random: Random): Int {
        val r = random.nextFloat() * 0.25f + 0.1f   // 0.1 - 0.35
        val g = random.nextFloat() * 0.5f + 0.5f    // 0.5 - 1.0
        val b = random.nextFloat() * 0.2f + 0.05f   // 0.05 - 0.25

        return Color(r, g, b, 1f).toIntBits()
    }

    fun initWorld(
        gridWidth: Int = 128,
        gridHeight: Int = 128
    ) {
        val random = Random(3)
        map?.let {
            for ((y, element) in it.withIndex()) {
                for ((x, element1) in element.withIndex()) {

                    val scaleX = x.toFloat() / WorldSpec.MAP_CELLS_PER_WORLD_CELL
                    val scaleY = y.toFloat() / WorldSpec.MAP_CELLS_PER_WORLD_CELL
                    // it — строки (высота), element — строка (ширина). Раньше ширину сравнивали
                    // с числом строк и наоборот: на квадратной карте незаметно, на
                    // неквадратной край пропускался не там.
                    if (x == 0 || x == element.size - 1 || y == 0 || y == it.size - 1) continue
                    if (element1) {
                        if (scaleX < gridWidth && scaleY < gridHeight) {
                            // Толща — все восемь соседей тоже террейн (то же правило, что у
                            // StaticTerrain.inBulk: она не идёт в мир физики). Её радиус и разброс —
                            // под «без дыр», у кромки — под плотный контур, см. BULK_RADIUS.
                            val bulk = isBulk(it, x, y, gridWidth, gridHeight)
                            val jitter = if (bulk) BULK_JITTER else EDGE_JITTER
                            val p = particleEntity.addParticle(
                                x = scaleX + (random.nextFloat() - 0.5f) * 2f * jitter,
                                y = scaleY + (random.nextFloat() - 0.5f) * 2f * jitter,
                                radius = if (bulk) BULK_RADIUS else EDGE_RADIUS,
                                color = getColor(random),
                                isCell = false,
                                isSub = false,
                                holderEntityIndex = -1,
                                dragCoefficient = 0.4f
                            )
                            // Клетка карты — по ней мост физики отделяет кромку от толщи, а микроскоп сшивает ткань (StaticTerrain).
                            particleEntity.terrainCell[p] = StaticTerrain.packCell(x, y)
                        }

                    } else {
//                        if (random.nextInt(60) == 1) {
//                            substancesEntity.addSubstance(
//                                x = scaleX,
//                                y = scaleY,
//                                color = Color.RED.toIntBits(),
//                                radius = 0.25f,
//                                subType = 0.toByte(),
//                            )
//                        }
                    }
                }
            }
        }
        var wall = 0
        forEachWallParticle(gridWidth, gridHeight) { x, y ->
            val p = particleEntity.addParticle(
                x = x,
                y = y,
                radius = WALL_RADIUS,
                color = getColor(random),
                isCell = false,
                isSub = false,
                holderEntityIndex = -1,
                dragCoefficient = 1.0f
            )
            // Номер в раскладке стенки — по нему мост физики привязывает частицу к стенке мира физики.
            particleEntity.terrainCell[p] = StaticTerrain.wallMark(wall++)
        }
        // Террейн и стенка до конца сессии не двигаются — в неподвижный слой сетки и в начало
        // списка не-клеток (ParticleEntity.bakeStaticGrid).
        particleEntity.bakeStaticGrid()
    }

    /**
     * Клетка карты ([x], [y]) — толща: все восемь соседей тоже становятся частицами террейна
     * (те же условия, что в [initWorld]: не край карты, внутри мира). То же правило, что у
     * StaticTerrain.inBulk, только по карте, а не по готовым частицам.
     */
    private fun isBulk(map: Array<BooleanArray>, x: Int, y: Int, gridWidth: Int, gridHeight: Int): Boolean {
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            if (!isTerrainCell(map, x + dx, y + dy, gridWidth, gridHeight)) return false
        }
        return true
    }

    private fun isTerrainCell(map: Array<BooleanArray>, x: Int, y: Int, gridWidth: Int, gridHeight: Int): Boolean {
        if (y <= 0 || y >= map.size - 1) return false
        val row = map[y]
        if (x <= 0 || x >= row.size - 1 || !row[x]) return false
        return x.toFloat() / WorldSpec.MAP_CELLS_PER_WORLD_CELL < gridWidth &&
            y.toFloat() / WorldSpec.MAP_CELLS_PER_WORLD_CELL < gridHeight
    }

    companion object {
        /** Радиус частицы стенки — как у клетки по умолчанию: ячейка сетки контактов физики не растёт. */
        const val WALL_RADIUS = 0.5f

        /**
         * ПЛОТНЫЙ ТЕРРЕЙН БЕЗ ДЫР (27.11, по просьбе автора). Частицы стоят по клеткам карты с шагом
         * s = 1/1.5 ≈ 0.667 и случайным сдвигом ±j по каждой оси. Дыра — место, до которого не
         * достаёт ни один круг; хуже всего центр квадрата из четырёх соседей: до угла s·√2/2 ≈ 0.471,
         * а со сдвигом — до 0.471 + j·√2. До 27.11 было r = 0.5 и j = 0.375 у всех: до 1.0 при радиусе
         * 0.5, отсюда дыры внутри и рваный контур.
         *
         * Толща (в мир физики не идёт, снаружи недостижима): r = 0.6 и j = 0.085: 0.471 + 0.085·√2 =
         * 0.591 < 0.6 (в упаковке снимка радиус 0.599) — дыр не бывает. Физике радиус толщи ничего не
         * стоит (её нет ни в мире физики, ни в парах сетки игры — ParticlePhysicsSystem.processStaticNeighbors),
         * а видеокарте стоит: круг шире — пиксель закрашивается чаще. Замер 27.11, A21s, мир с 66
         * тысячами частиц террейна, отладочный: прежняя раскладка (с дырами) — 22.1 кадра, r 0.6 — 19.7,
         * r 0.75 (j 0.19, рисунок живее) — 17.1; шаг физики у всех трёх один (77–85 мс).
         *
         * Кромка (в мире физики — стенка): радиус прежний 0.5 — на него рассчитаны ячейки сеток
         * касаний; сдвиг j = 0.1: соседи вдоль контура не дальше s + 2j = 0.867 < 1 — всегда
         * перекрываются, контур сплошной. Частиц столько же, сколько было.
         */
        const val BULK_RADIUS = 0.6f
        const val BULK_JITTER = 0.085f
        const val EDGE_RADIUS = 0.5f
        const val EDGE_JITTER = 0.1f

        /**
         * СТЕНКА ВДОЛЬ РАМКИ МИРА: частицы с шагом 1/1.5 клетки вплотную к рамке — снизу, слева,
         * справа, сверху. Одна раскладка на двоих: террейн игры рисует эти частицы, мир физики
         * ставит на те же места неподвижные бессмертные (WorldPhysics.buildWall), и тела
         * останавливаются о видимую стенку, а не о рамку за ней. Соседние частицы перекрываются
         * (шаг 0.67 < диаметра 1), поэтому щелей нет и контур между ними не нужен.
         */
        inline fun forEachWallParticle(gridWidth: Int, gridHeight: Int, action: (x: Float, y: Float) -> Unit) {
            for (i in 1..<(gridWidth / 2 * 3)) action(i.toFloat() / 1.5f, 0.5f)
            for (i in 1..<(gridHeight / 2 * 3)) action(0.5f, i.toFloat() / 1.5f)
            for (i in 1..<(gridHeight / 2 * 3)) action(gridWidth - 0.5f, i.toFloat() / 1.5f)
            for (i in 1..<(gridWidth / 2 * 3)) action(i.toFloat() / 1.5f, gridHeight - 0.5f)
        }
    }
}
