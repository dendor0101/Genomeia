package io.github.some_example_name.old.systems.genomics.library

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.systems.genomics.genome.decodeGenome
import io.github.some_example_name.old.systems.genomics.genome.encodeGenome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Чтение старого JSON. Образцы — куски настоящих файлов из ассетов до 01de851: «Hypha» в
 * формате последнего писателя (colorHsv) и «Burdock leaf» в формате Json libGDX (объект
 * color, метки "class", явные null).
 */
class LegacyGenomeJsonTest {

    private val hypha = """
        {
        "name": "Hypha",
        "genomeStageInstruction": [
            {
                "cellActions": {
                    "class": "java.util.LinkedHashMap",
                    "0": {
                        "divide": {
                            "id": "1",
                            "angle": 0.02165,
                            "cellType": 0,
                            "physicalLink": { "0": { "length": 30 } },
                            "colorHsv": "218a21ff"
                        }
                    }
                }
            },
            {
                "cellActions": {
                    "class": "java.util.LinkedHashMap",
                    "1": {
                        "divide": {
                            "id": "6",
                            "angle": 0.03667573,
                            "cellType": 19,
                            "physicalLink": {
                                "1": { "length": 29.999989, "directedNeuronLink": 6 },
                                "4": { "length": 31.025742, "directedNeuronLink": 4 },
                                "5": { "length": 31.864334 }
                            },
                            "colorHsv": "8a0000ff",
                            "lengthDirected": 170,
                            "angleDirected": 1.5,
                            "colorRecognition": 3
                        }
                    },
                    "0": {
                        "mutate": {
                            "cellType": 22,
                            "physicalLink": {},
                            "colorHsv": "4169e0ff",
                            "funActivation": 0,
                            "a": 1, "b": 1, "c": 0,
                            "isSum": true,
                            "pheromoneType": 11
                        }
                    }
                }
            }
        ]
        }
    """.trimIndent()

    private val burdock = """
        {
        "name": "Burdock leaf",
        "genomeStageInstruction": [
            {
                "cellActions": {
                    "class": "java.util.LinkedHashMap",
                    "0": {
                        "divide": {
                            "id": "1",
                            "angle": -0.008406651,
                            "cellType": 0,
                            "indexOfNew": -1,
                            "physicalLink": {
                                "class": "java.util.LinkedHashMap",
                                "0": { "length": 30.000084, "isNeuronal": false, "weight": null, "directedNeuronLink": null }
                            },
                            "color": { "r": 0.133, "g": 0.545, "b": 0.133, "a": 1 },
                            "angleDirected": 0,
                            "startDirectionId": null,
                            "funActivation": null,
                            "a": null, "b": null, "c": null,
                            "isSum": null,
                            "colorRecognition": null,
                            "lengthDirected": null
                        },
                        "mutate": {
                            "id": "-1",
                            "angle": null,
                            "cellType": 0,
                            "indexOfNew": -1,
                            "physicalLink": { "class": "java.util.LinkedHashMap" },
                            "color": { "r": 0.12156863, "g": 0.38039216, "b": 0.12156863, "a": 1 },
                            "lengthDirected": null
                        }
                    }
                }
            },
            {
                "cellActions": {
                    "class": "java.util.LinkedHashMap",
                    "1": { "divide": null, "mutate": { "id": "-1", "cellType": null, "physicalLink": { "class": "java.util.LinkedHashMap" } } }
                }
            }
        ]
        }
    """.trimIndent()

    @Test
    fun `формат colorHsv - единицы, нейросвязи и порядок как в старом чтении`() {
        val genome = LegacyGenomeJson.parse(hypha, "fallback")

        assertEquals("Hypha", genome.name)
        assertEquals(LegacyGenomeJson.VERSION, genome.version)
        assertEquals(2, genome.stageInstruction.size)

        val zygote = genome.stageInstruction[0].cellActions.getValue(0).divide!!
        assertEquals(1, zygote.id)
        assertEquals(0.02165f, zygote.angle)
        assertEquals("leaf", zygote.cellType)
        assertEquals(Color.valueOf("218a21ff"), zygote.color)
        // Длина в JSON — в пикселях старой сетки, в геноме — в клетках.
        assertEquals(30f / 40f, zygote.physicalLink.getValue(0)!!.length)

        val stage = genome.stageInstruction[1].cellActions
        // Порядок клеток в стадии — как в файле, а не по возрастанию ключа.
        assertEquals(listOf(1, 0), stage.keys.toList())

        val eye = stage.getValue(1).divide!!
        assertEquals(6, eye.id)
        assertEquals("producer", eye.cellType)
        assertEquals(170f / 40f, eye.lengthDirected)
        assertEquals(1.5f, eye.angleDirected)
        assertEquals(3, eye.colorRecognition)
        assertEquals(listOf(1, 4, 5), eye.physicalLink.keys.toList())
        assertTrue(eye.physicalLink.getValue(1)!!.isNeuronal)
        assertEquals(6, eye.physicalLink.getValue(1)!!.directedNeuronLink)
        assertFalse(eye.physicalLink.getValue(5)!!.isNeuronal)
        assertNull(eye.physicalLink.getValue(5)!!.directedNeuronLink)

        val mutate = stage.getValue(0).mutate!!
        assertEquals(-1, mutate.id)
        assertEquals("pheromone_emitter", mutate.cellType)
        assertEquals("linear", mutate.funActivation)
        assertEquals(1f, mutate.a)
        assertEquals(1f, mutate.b)
        assertEquals(0f, mutate.c)
        assertEquals(true, mutate.isSum)
        assertEquals(11, mutate.pheromoneType)
        assertNull(stage.getValue(0).divide)
    }

    @Test
    fun `формат Json libGDX - объект color, метки class и явные null`() {
        val genome = LegacyGenomeJson.parse(burdock, "fallback")

        val cell = genome.stageInstruction[0].cellActions.getValue(0)
        assertEquals(Color(0.133f, 0.545f, 0.133f, 1f), cell.divide!!.color)
        assertEquals(listOf(0), cell.divide.physicalLink.keys.toList())
        val link = cell.divide.physicalLink.getValue(0)!!
        assertFalse(link.isNeuronal)
        assertNull(link.weight)
        assertNull(link.directedNeuronLink)
        assertNull(cell.divide.funActivation)
        assertNull(cell.divide.isSum)
        assertEquals(0f, cell.divide.angleDirected)

        assertEquals(-1, cell.mutate!!.id)
        assertTrue(cell.mutate.physicalLink.isEmpty())

        val second = genome.stageInstruction[1].cellActions
        assertEquals(listOf(1), second.keys.toList())
        assertNull(second.getValue(1).divide)
        assertNull(second.getValue(1).mutate!!.cellType)
    }

    @Test
    fun `переведённый геном переживает запись в genome без потерь`() {
        for (text in listOf(hypha, burdock)) {
            val genome = LegacyGenomeJson.parse(text, "fallback")
            val decoded = decodeGenome(encodeGenome(genome))
            assertEquals(genome, decoded)
            // Порядок — часть генома: от него зависит порядок деления клеток в тике.
            assertEquals(
                genome.stageInstruction.map { it.cellActions.keys.toList() },
                decoded.stageInstruction.map { it.cellActions.keys.toList() }
            )
        }
    }

    @Test
    fun `связь null - мутация снимает связь - переживает запись`() {
        // Старый писатель клал null-связь как "ключ": null. В мутации это «убрать связь».
        val genome = LegacyGenomeJson.parse(
            """{ "genomeStageInstruction": [ { "cellActions": { "0": { "mutate": { "physicalLink": { "3": null, "4": { "length": 40 } } } } } } ] }""",
            "x"
        )
        val links = genome.stageInstruction[0].cellActions.getValue(0).mutate!!.physicalLink
        assertEquals(listOf(3, 4), links.keys.toList())
        assertNull(links.getValue(3))
        assertEquals(genome, decodeGenome(encodeGenome(genome)))
    }

    /**
     * Настоящие файлы из ассетов до 01de851 (test/resources/legacy-genomes). Ожидаемые
     * числа сняты независимо — обычным json-парсером Python по тем же файлам.
     */
    @Test
    fun `настоящие старые геномы читаются целиком`() {
        // имя -> стадии, деления, мутации, связи, нейросвязи
        val expected = mapOf(
            "Hypha" to listOf(4, 8, 0, 18, 5),
            "Morphogenesis" to listOf(2, 1, 1, 1, 0),
            "Maggot" to listOf(22, 59, 37, 282, 12),
            "Leech" to listOf(56, 72, 70, 219, 30),
            "Tardigrade" to listOf(26, 68, 183, 311, 43)
        )
        for ((name, counts) in expected) {
            val text = javaClass.getResource("/legacy-genomes/$name.json")!!.readText()
            val genome = LegacyGenomeJson.parse(text, "fallback")
            val actions = genome.stageInstruction.flatMap { it.cellActions.values }
            val links = actions.flatMap { listOfNotNull(it.divide, it.mutate) }.flatMap { it.physicalLink.values }
            val actual = listOf(
                genome.stageInstruction.size,
                actions.count { it.divide != null },
                actions.count { it.mutate != null },
                links.size,
                links.count { it?.isNeuronal == true }
            )
            assertEquals(counts, actual, name)
            assertEquals(name, genome.name)
            assertEquals(genome, decodeGenome(encodeGenome(genome)), name)
        }
    }

    @Test
    fun `кость из JSON становится листом своего цвета - в делении и в мутации`() {
        // Старый номер 2 — кость. Цвет — всеми тремя способами, какие встречаются в файлах.
        val genome = LegacyGenomeJson.parse(
            """
            { "genomeStageInstruction": [ { "cellActions": {
                "0": { "divide": { "id": "1", "cellType": 2, "colorHsv": "ffc0caff" },
                       "mutate": { "id": "-1", "cellType": 2, "color": { "r": 0.25, "g": 0.5, "b": 0.75, "a": 1 } } },
                "1": { "divide": { "id": "2", "cellType": 2 },
                       "mutate": { "id": "-1", "cellType": 5, "colorHsv": "11223344" } }
            } } ] }
            """,
            "x"
        )
        val cells = genome.stageInstruction[0].cellActions
        val first = cells.getValue(0)
        assertEquals("leaf", first.divide!!.cellType)
        assertEquals(Color.valueOf("ffc0caff"), first.divide.color)
        assertEquals("leaf", first.mutate!!.cellType)
        assertEquals(Color(0.25f, 0.5f, 0.75f, 1f), first.mutate.color)
        // Без цвета остаётся без цвета: деление красит такую клетку в белый, как и кость.
        val second = cells.getValue(1)
        assertEquals("leaf", second.divide!!.cellType)
        assertNull(second.divide.color)
        // Остальные типы не трогаются.
        assertEquals("muscle", second.mutate!!.cellType)
        assertEquals(Color.valueOf("11223344"), second.mutate.color)
    }

    /**
     * Те же настоящие файлы: костей после чтения нет, листьев ровно столько, сколько было
     * листьев и костей вместе. Числа сняты независимо — ConvertFrom-Json в PowerShell.
     */
    @Test
    fun `в настоящих старых геномах все кости стали листьями`() {
        // имя -> кости в файле, листья в файле
        val expected = mapOf(
            "Hypha" to (0 to 3),
            "Morphogenesis" to (0 to 0),
            "Maggot" to (11 to 60),
            "Leech" to (9 to 7),
            "Tardigrade" to (10 to 104)
        )
        for ((name, counts) in expected) {
            val text = javaClass.getResource("/legacy-genomes/$name.json")!!.readText()
            val actions = LegacyGenomeJson.parse(text, "fallback").stageInstruction
                .flatMap { it.cellActions.values }
                .flatMap { listOfNotNull(it.divide, it.mutate) }
            assertEquals(0, actions.count { it.cellType == "bone" }, name)
            assertEquals(counts.first + counts.second, actions.count { it.cellType == "leaf" }, name)
        }
    }

    /**
     * Сжатый «gen4.json» игрока: в последней стадии корень делится на зиготу с номером 0.
     * Раньше рост такого генома падал на ключе корня (CellEntity.addCell).
     */
    @Test
    fun `деление с номером корня получает свободный номер, остальное как в файле`() {
        val genome = LegacyGenomeJson.parse(
            """
            { "genomeStageInstruction": [
                { "cellActions": { "0": { "divide": { "id": "1", "angle": 1.57, "cellType": 0, "physicalLink": { "0": { "length": 25 } } } } } },
                { "cellActions": {
                    "0": { "mutate": { "id": null, "colorHsv": "c71585ff" } },
                    "1": { "divide": { "id": "2", "angle": 1.57, "cellType": 4, "physicalLink": { "1": { "length": 25 }, "7": { "length": 25 } } } } } },
                { "cellActions": {
                    "0": { "divide": { "id": "0", "angle": 4.57, "cellType": 18, "physicalLink": {}, "colorHsv": "ffffffff" } },
                    "2": { "divide": { "id": "3", "angle": 3.14, "cellType": 4, "physicalLink": { "2": { "length": 25 } } } } } }
            ] }
            """,
            "x"
        )
        val last = genome.stageInstruction[2].cellActions
        // Самый большой номер в файле — 7 (ссылка связи), значит новый — 8.
        val bud = last.getValue(0).divide!!
        assertEquals(8, bud.id)
        assertEquals("zygote", bud.cellType)
        assertEquals(4.57f, bud.angle)
        assertEquals(Color.WHITE, bud.color)
        // Порядок клеток в стадии и соседние деления — как в файле.
        assertEquals(listOf(0, 2), last.keys.toList())
        assertEquals(3, last.getValue(2).divide!!.id)
        assertEquals(listOf(1, 2), genome.stageInstruction.take(2).map { it.cellActions.values.firstNotNullOf { a -> a.divide }.id })
        assertEquals(genome, decodeGenome(encodeGenome(genome)))
    }

    @Test
    fun `без имени в файле берётся запасное`() {
        val genome = LegacyGenomeJson.parse("""{ "genomeStageInstruction": [ { "cellActions": {} } ] }""", "из файла")
        assertEquals("из файла", genome.name)
    }

    @Test
    fun `не геном - понятная ошибка, а не падение`() {
        assertFailsWith<IllegalArgumentException> { LegacyGenomeJson.parse("{}", "x") }
        assertFailsWith<IllegalArgumentException> { LegacyGenomeJson.parse("[1, 2]", "x") }
        assertFailsWith<IllegalArgumentException> { LegacyGenomeJson.parse("""{ "genomeStageInstruction": [] }""", "x") }
        assertFailsWith<IllegalArgumentException> {
            LegacyGenomeJson.parse("""{ "genomeStageInstruction": [ { "cellActions": { "abc": {} } } ] }""", "x")
        }
        assertFailsWith<IllegalArgumentException> { LegacyGenomeJson.parse("{ \"genomeStageInstruction\": [", "x") }
    }

    @Test
    fun `JSON узнаётся по первому значащему символу`() {
        assertTrue(LegacyGenomeJson.looksLikeJson("  \n{\"a\":1}".toByteArray()))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "{}".toByteArray()
        assertTrue(LegacyGenomeJson.looksLikeJson(bom))
        assertFalse(LegacyGenomeJson.looksLikeJson("genomeia\u0000\u0000\u0000\u0001".toByteArray()))
        assertFalse(LegacyGenomeJson.looksLikeJson(ByteArray(0)))
    }
}
