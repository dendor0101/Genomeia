package io.github.some_example_name.old.features.worldeditor

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.SubstancesEntity
import io.github.some_example_name.old.physics.bridge.TerrainBodies
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
                            val p = particleEntity.addParticle(
                                x = scaleX + (random.nextFloat() - 0.5f) * 0.1f,
                                y = scaleY + (random.nextFloat() - 0.5f) * 0.1f,
                                radius = 0.5f,
                                color = getColor(random),
                                isCell = false,
                                isSub = false,
                                holderEntityIndex = -1,
                                dragCoefficient = 0.4f
                            )
                            // Клетка карты — по ней мост физики режет террейн на плитки (TerrainBodies).
                            particleEntity.terrainCell[p] = TerrainBodies.packCell(x, y)
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
            particleEntity.terrainCell[p] = TerrainBodies.wallMark(wall++)
        }
    }

    companion object {
        /** Радиус частицы стенки — как у клетки по умолчанию: ячейка сетки контактов физики не растёт. */
        const val WALL_RADIUS = 0.5f

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
