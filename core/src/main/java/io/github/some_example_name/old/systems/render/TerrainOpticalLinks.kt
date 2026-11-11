package io.github.some_example_name.old.systems.render

import io.github.some_example_name.old.entities.ParticleEntity

/**
 * ТЕРРЕЙН — ОДИН КУСОК ТКАНИ В МИКРОСКОПЕ. Частицы террейна (ParticleEntity.terrainCell — клетка карты)
 * связываются в граф изображения (OpticalLinks) с соседями по сетке карты: вправо, вверх, по диагонали
 * (1, 1) и обратно — шесть соседей, сетка из треугольников, как у тела. Из графа микроскоп строит одну
 * сетку на весь террейн (группа OpticalLinks.TERRAIN_GROUP); террейн неподвижен, и сетка строится раз
 * на мир.
 *
 * Сосед ищется по плотной таблице «клетка карты → частица» ([grid]): по 4 байта на клетку карты
 * (мир 128 — 192×192 клеток, 147 КБ; мир 512 — 2.4 МБ). Террейн в списке не-клеток лежит в порядке
 * карты, и таблица читается почти потоком. Хэш-таблица на каждом кадре дала бы случайный промах на
 * каждого соседа.
 *
 * Таблица — только для рендера, не состояние мира. Строится заново, когда перестала совпадать с миром
 * (новая карта, загрузка): [prepare] сверяет каждую частицу террейна со своей клеткой, сосед
 * проверяется при чтении — живая не-клетка с той самой клеткой карты.
 */
internal class TerrainOpticalLinks(private val particles: ParticleEntity) {
    private var grid = IntArray(0)
    private var width = 0
    private var height = 0

    /** Однопоточно, раз на полигональный кадр до записи графа: сверка таблицы с миром, при расхождении — новая. */
    fun prepare() {
        val nonCells = particles.nonCellList
        val cells = particles.terrainCell
        for (k in 0 until nonCells.size) {
            val p = nonCells.getInt(k)
            val c = cells[p]
            if (c >= 0 && at(cellX(c), cellY(c)) != p) { rebuild(); return }
        }
    }

    private fun rebuild() {
        val nonCells = particles.nonCellList
        val cells = particles.terrainCell
        var w = 0; var h = 0
        for (k in 0 until nonCells.size) {
            val c = cells[nonCells.getInt(k)]
            if (c >= 0) { w = maxOf(w, cellX(c) + 1); h = maxOf(h, cellY(c) + 1) }
        }
        width = w; height = h
        if (grid.size < w * h) grid = IntArray(w * h)
        grid.fill(-1, 0, w * h)
        for (k in 0 until nonCells.size) {
            val p = nonCells.getInt(k)
            val c = cells[p]
            if (c >= 0) grid[cellY(c) * w + cellX(c)] = p
        }
    }

    /** Частица террейна в клетке карты ([x], [y]); −1 — нет или вне таблицы. */
    private fun at(x: Int, y: Int): Int = if (x < 0 || y < 0 || x >= width || y >= height) -1 else grid[y * width + x]

    /** Сосед частицы [p] клетки [c] в направлении [d] — живая частица террейна той клетки карты, иначе −1. */
    private fun neighbor(c: Int, d: Int): Int {
        val x = cellX(c) + DX[d]; val y = cellY(c) + DY[d]
        val q = at(x, y)
        if (q < 0 || particles.positionInNonCellList[q] < 0 || particles.terrainCell[q] != y * 65536 + x) return -1
        return q
    }

    /** Сколько соседей у частицы террейна [p] (не террейн — 0). Только чтение: с воркеров. */
    fun count(p: Int): Int {
        val c = particles.terrainCell[p]
        if (c < 0) return 0
        var n = 0
        for (d in 0 until DIRECTIONS) if (neighbor(c, d) >= 0) n++
        return n
    }

    /**
     * Соседи частицы террейна [p] — номерами инстансов снимка ([nonCellStart] + позиция в списке не-клеток) —
     * в [out] с места [at]; возвращает место за последним. Только чтение мира: с воркеров.
     */
    fun write(p: Int, nonCellStart: Int, out: IntArray, at: Int): Int {
        val c = particles.terrainCell[p]
        if (c < 0) return at
        var k = at
        for (d in 0 until DIRECTIONS) {
            val q = neighbor(c, d)
            if (q >= 0) out[k++] = nonCellStart + particles.positionInNonCellList[q]
        }
        return k
    }

    /** Верхняя граница числа записей соседей на кадр — для резерва графа. */
    fun capacity(): Int = particles.nonCellList.size * DIRECTIONS

    companion object {
        private const val DIRECTIONS = 6
        private val DX = intArrayOf(1, -1, 0, 0, 1, -1)
        private val DY = intArrayOf(0, 0, 1, -1, 1, -1)
        private fun cellX(c: Int) = c and 0xFFFF
        private fun cellY(c: Int) = c ushr 16
    }
}
