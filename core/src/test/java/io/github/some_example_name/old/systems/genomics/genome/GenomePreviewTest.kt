package io.github.some_example_name.old.systems.genomics.genome

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Снимок для превью в файле генома: переживает запись и чтение и не раздувает файл. */
class GenomePreviewTest {

    private fun preview(cells: Int) = GenomePreview(
        cellTypeKeys = listOf("leaf", "muscle"),
        cellType = IntArray(cells) { it % 2 },
        x = FloatArray(cells) { it * 0.5f - cells * 0.25f },
        y = FloatArray(cells) { (it % 7) * 0.5f },
        radius = FloatArray(cells) { 0.25f + (it % 3) * 0.1f },
        angleCos = FloatArray(cells) { 1f },
        angleSin = FloatArray(cells) { 0f },
        // Цвет с альфой в старшем байте — отрицательное число, на нём varint и раздувается.
        colorBytes = GenomePreview.colorBytesOf(IntArray(cells) { (0xFF shl 24) or it }),
        cellGenomeId = IntArray(cells) { it }
    )

    private val genome = Genome(version = 24, name = "g")

    @Test
    fun `снимок переживает запись и чтение`() {
        val original = genome.copy(preview = preview(100))
        val decoded = decodeGenome(encodeGenome(original))
        assertEquals(original, decoded)
        // Цвет — числом, как его хранит ParticleEntity.color, в том числе с альфой в знаке.
        assertEquals((0xFF shl 24) or 42, assertNotNull(decoded.preview).color(42))
    }

    /**
     * Пустое тело — тоже снимок. Если бы он читался как null, GenomeImport.withBaking
     * выращивал бы такой геном заново на каждом запуске.
     */
    @Test
    fun `пустой снимок не читается как отсутствующий`() {
        val decoded = decodeGenome(encodeGenome(genome.copy(preview = GenomePreview())))
        assertEquals(0, assertNotNull(decoded.preview).cellCount)
    }

    /**
     * На клетку 5 float, 4 байта цвета и пара байт на тип и genomeId — около 27 байт.
     * Без упаковки массивов и без фиксированного цвета выходило бы под 50: тег перед каждым
     * числом и 10 байт varint на отрицательный цвет.
     */
    @Test
    fun `снимок укладывается в 28 байт на клетку`() {
        val cells = 1000
        val withPreview = encodeGenome(genome.copy(preview = preview(cells))).size
        val without = encodeGenome(genome).size
        val perCell = (withPreview - without).toDouble() / cells
        assertTrue(perCell <= 28.0, "снимок занимает $perCell байт на клетку")
    }

    @Test
    fun `рамка тела считается с радиусами`() {
        val p = GenomePreview(
            cellTypeKeys = listOf("leaf"),
            cellType = intArrayOf(0, 0),
            x = floatArrayOf(-1f, 2f),
            y = floatArrayOf(0f, -3f),
            radius = floatArrayOf(0.5f, 0.25f),
            angleCos = floatArrayOf(1f, 1f),
            angleSin = floatArrayOf(0f, 0f),
            colorBytes = ByteArray(8),
            cellGenomeId = intArrayOf(0, 1)
        )
        assertEquals(3.25f, p.halfExtent())
    }
}
