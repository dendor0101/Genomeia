package io.github.some_example_name.render.pack

import io.github.some_example_name.render.DebugFilter
import io.github.some_example_name.render.RenderFrame
import org.junit.Test
import java.io.File
import kotlin.math.round
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Номера рисунков и частоты анимации отладочного режима против шейдера.
 *
 * Номера живут в двух местах — CellLook и cell_look.glsl — и разъехаться могут молча:
 * клетка просто получила бы чужой рисунок. Частоты — только в шейдере, и проверить их
 * можно лишь чтением исходника: часы анимации идут по кругу (RenderFrame.TIME_WRAP), и
 * частота, при которой за оборот не укладывается целое число периодов, даёт рывок.
 */
class CellLookTest {

    @Test
    fun `номера рисунков совпадают с шейдером`() {
        val glsl = shaderSource("cell_look.glsl")
        val expected = mapOf(
            "P_PLAIN" to CellLook.PLAIN,
            "P_CHLOROPLASTS" to CellLook.CHLOROPLASTS,
            "P_FAT_DROPLET" to CellLook.FAT_DROPLET,
            "P_OSTEON" to CellLook.OSTEON,
            "P_FLAGELLUM_BASE" to CellLook.FLAGELLUM_BASE,
            "P_NEURON" to CellLook.NEURON,
            "P_MUSCLE" to CellLook.MUSCLE,
            "P_CILIA_INWARD" to CellLook.CILIA_INWARD,
            "P_STEM" to CellLook.STEM,
            "P_EXCRETA" to CellLook.EXCRETA,
            "P_SUCTION_RINGS" to CellLook.SUCTION_RINGS,
            "P_STICKY_BUMPS" to CellLook.STICKY_BUMPS,
            "P_PUMP_NEEDLES" to CellLook.PUMP_NEEDLES,
            "P_PIGMENTS" to CellLook.PIGMENTS,
            "P_EYE" to CellLook.EYE,
            "P_SYNAPSE" to CellLook.SYNAPSE,
            "P_TOUCH_NET" to CellLook.TOUCH_NET,
            "P_ZYGOTE" to CellLook.ZYGOTE,
            "P_DIVIDING" to CellLook.DIVIDING,
            "P_EMITTER_GRANULES" to CellLook.EMITTER_GRANULES,
            "P_LONG_CILIA" to CellLook.LONG_CILIA,
            "P_SPIKES" to CellLook.SPIKES,
            "P_SHORT_CILIA" to CellLook.SHORT_CILIA,
            "P_CHIP" to CellLook.CHIP,
            "P_COUNT" to CellLook.COUNT
        )
        val declared = Regex("""const int (P_\w+) = (\d+);""").findAll(glsl)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertEquals(expected, declared)
        assertEquals((0 until CellLook.COUNT).toSet(), expected.values.toSet() - CellLook.COUNT, "номера без дыр")
    }

    @Test
    fun `частоты анимации укладываются в оборот часов целым числом`() {
        val glsl = shaderSource("cell_common.glsl")
        val frequencies = Regex("""(?:cycle|wave)\(([\d.]+),""").findAll(glsl).map { it.groupValues[1] } +
            Regex("""([\d.]+) \* u_time""").findAll(glsl).map { it.groupValues[1] }
        val list = frequencies.toList()
        assertTrue(list.size > 10, "регулярки перестали находить частоты: $list")
        for (hz in list) {
            val turns = hz.toDouble() * RenderFrame.TIME_WRAP
            assertEquals(round(turns), turns, 1e-6, "частота $hz: за ${RenderFrame.TIME_WRAP} с не целое число оборотов")
        }
    }

    @Test
    fun `фильтр энергии — заполненность от синего к пурпурному`() {
        assertEquals(0, CellLook.energyFilterByte(0f, 5f))
        assertEquals(255, CellLook.energyFilterByte(5f, 5f))
        assertEquals(128, CellLook.energyFilterByte(2.5f, 5f))
        // Жир с тем же запасом заполнен меньше — и синее.
        assertTrue(CellLook.energyFilterByte(5f, 20f) < CellLook.energyFilterByte(5f, 5f))
        // Перебор, минус и мусор не вылезают за байт.
        assertEquals(255, CellLook.energyFilterByte(50f, 5f))
        assertEquals(0, CellLook.energyFilterByte(-1f, 5f))
        assertEquals(0, CellLook.energyFilterByte(1f, 0f))
        assertEquals(0, CellLook.energyFilterByte(Float.NaN, 5f))
    }

    /** cell.vert, filterColor: знаковый байт / 127. */
    private fun shaderSignal(byte: Int): Float = (if (byte > 127) byte - 256 else byte) / 127f

    @Test
    fun `фильтр сигнала — ноль ровно ноль, знак сохраняется, слабое видно, сильное не сливается`() {
        assertEquals(0f, shaderSignal(CellLook.signalFilterByte(0f)))
        assertEquals(0f, shaderSignal(CellLook.signalFilterByte(Float.NaN)))
        // Половина шкалы — на колене.
        assertEquals(0.5f, shaderSignal(CellLook.signalFilterByte(CellLook.SIGNAL_KNEE)), 1f / 127f)
        assertEquals(-0.5f, shaderSignal(CellLook.signalFilterByte(-CellLook.SIGNAL_KNEE)), 1f / 127f)
        // Слабый сигнал заметно отличается от нуля (линейный зажим дал бы 0.05).
        assertTrue(shaderSignal(CellLook.signalFilterByte(0.05f)) > 0.15f)
        // Сильные различимы между собой, а не слиты в единицу.
        val one = CellLook.signalFilterByte(1f)
        val four = CellLook.signalFilterByte(4f)
        val twenty = CellLook.signalFilterByte(20f)
        assertTrue(one < four && four < twenty, "$one $four $twenty")
        assertTrue(shaderSignal(CellLook.signalFilterByte(Float.POSITIVE_INFINITY)) in 0f..1f)
    }

    @Test
    fun `номера фильтров совпадают с шейдером`() {
        val vert = shaderSource("cell.vert")
        fun constInt(name: String): Int =
            Regex("""const int $name = (\d+);""").find(vert)?.groupValues?.get(1)?.toInt()
                ?: error("в cell.vert нет $name")
        assertEquals(DebugFilter.ENERGY.ordinal, constInt("FILTER_ENERGY"))
        assertEquals(DebugFilter.SIGNAL.ordinal, constInt("FILTER_SIGNAL"))
        assertEquals(DebugFilter.COLLISION.ordinal, constInt("FILTER_COLLISION"))
        assertEquals(0, DebugFilter.ORIGINAL.ordinal, "u_filter = 0 — без фильтра")
    }

    private fun shaderSource(name: String): String {
        val candidates = listOf("../assets/shaders/debug_view", "assets/shaders/debug_view")
            .map { File(it, name) }
        val file = candidates.firstOrNull { it.isFile }
            ?: error("не найден $name, искал: ${candidates.joinToString { it.absolutePath }}")
        return file.readText()
    }
}
