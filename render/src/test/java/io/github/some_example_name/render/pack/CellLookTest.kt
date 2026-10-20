package io.github.some_example_name.render.pack

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

    private fun shaderSource(name: String): String {
        val candidates = listOf("../assets/shaders/debug_view", "assets/shaders/debug_view")
            .map { File(it, name) }
        val file = candidates.firstOrNull { it.isFile }
            ?: error("не найден $name, искал: ${candidates.joinToString { it.absolutePath }}")
        return file.readText()
    }
}
