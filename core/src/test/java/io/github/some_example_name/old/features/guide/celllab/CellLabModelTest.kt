package io.github.some_example_name.old.features.guide.celllab

import io.github.some_example_name.old.cells.base.CellPalette
import io.github.some_example_name.old.cells.base.CellTypes
import io.github.some_example_name.old.journal.CellLabInput
import org.junit.Test
import kotlin.test.*

class CellLabModelTest {
    @Test fun `сценарии покрывают все доступные клетки`() {
        assertEquals(CellPalette.FULL.cellChoices(CellTypes.createAll(), null).map { it.key }.toSet(), CellLabCatalog.keys.toSet())
    }

    @Test fun `все сценарии живут и точно повторяются без окна`() {
        for (key in CellLabCatalog.keys) {
            val a = CellLabModel(key)
            val b = CellLabModel(key)
            try {
                repeat(600) { tick ->
                    if (tick == 10) {
                        val input = CellLabInput("set", "signal", 1f)
                        a.apply(input); b.apply(input)
                    }
                    a.step(); b.step()
                    if (tick % 60 == 59) assertEquals(a.stateHash(), b.stateHash(), "$key tick=$tick")
                    for (i in 0..a.p.lastId) if (a.p.isAlive[i]) {
                        assertTrue(a.p.x[i].isFinite() && a.p.y[i].isFinite(), "$key particle=$i")
                    }
                }
                assertTrue(a.c.aliveList.size <= CellLabModel.MAX_CELLS, key)
            } finally { a.dispose(); b.dispose() }
        }
    }

    @Test fun `глаз читает выбранные каналы первой цели`() {
        val lab = CellLabModel("eye")
        try {
            lab.apply(CellLabInput("set", "channels", 2f))
            repeat(4) { lab.step() }
            assertEquals(1f, lab.c.neuronImpulseOutput[lab.primary], 0.01f)
            lab.apply(CellLabInput("set", "channels", 1f))
            repeat(4) { lab.step() }
            assertEquals(0f, lab.c.neuronImpulseOutput[lab.primary], 0.01f)
            lab.apply(CellLabInput("set", "range", 2f))
            lab.apply(CellLabInput("set", "channels", 2f))
            repeat(4) { lab.step() }
            assertEquals(0f, lab.c.neuronImpulseOutput[lab.primary], 0.01f)
        } finally { lab.dispose() }
    }

    @Test fun `мышца действительно меняет ширину тела а присоска сопротивляется тяге`() {
        fun span(signal: Float): Float {
            val lab = CellLabModel("muscle")
            try {
                lab.apply(CellLabInput("set", "signal", signal))
                repeat(180) { lab.step() }
                return kotlin.math.abs(lab.c.getX(lab.companion) - lab.c.getX(lab.source))
            } finally { lab.dispose() }
        }
        val short = span(-1f); val long = span(1f)
        assertTrue(long > short + 0.3f, "short=$short long=$long")
        fun displacement(signal: Float): Float {
            val lab = CellLabModel("suction_cup")
            try {
                lab.apply(CellLabInput("set", "signal", signal))
                lab.step()
                lab.apply(CellLabInput("grab", x = 4f, y = 4f))
                lab.apply(CellLabInput("drag", x = 7f, y = 4f))
                repeat(60) { lab.step() }
                return kotlin.math.abs(lab.c.getX(lab.primary) - 4f)
            } finally { lab.dispose() }
        }
        val free = displacement(0f); val held = displacement(1f)
        assertTrue(free > held + 0.5f, "free=$free held=$held")
    }

    @Test fun `убийство и рост происходят в живом мире`() {
        for (key in listOf("zygote", "producer", "synapse", "pheromone_sensor")) {
            val lab = CellLabModel(key)
            try {
                lab.apply(CellLabInput("set", "signal", 1f))
                repeat(1800) { lab.step() }
                println("LAB $key cells=${lab.c.aliveList.size} kills=${lab.kills} organs=${lab.world.organEntity.aliveList.size}" +
                    " energy=${if (lab.primaryAlive) lab.c.energy[lab.primary] else -1f}" +
                    " output=${if (lab.primaryAlive) lab.c.neuronImpulseOutput[lab.primary] else -1f}")
                when (key) {
                    "zygote" -> assertEquals(2, lab.c.aliveList.size)
                    "producer" -> assertTrue(lab.world.organEntity.aliveList.size >= 2)
                    "synapse" -> assertTrue(lab.c.getWeight(lab.primary) > 0.5f)
                    "pheromone_sensor" -> assertTrue(lab.c.neuronImpulseOutput[lab.primary] > 0f)
                }
            } finally { lab.dispose() }
        }
    }

    @Test fun `долгий опыт сохраняет пределы населения и памяти`() {
        for (key in listOf("producer", "excreta", "punisher", "sucker", "eye", "synapse")) {
            val lab = CellLabModel(key)
            try {
                lab.apply(CellLabInput("set", "signal", 1f))
                repeat(600) { lab.step() }
                val times = DoubleArray(5)
                repeat(times.size) { batch ->
                    val start = System.nanoTime()
                    repeat(600) { lab.step() }
                    times[batch] = (System.nanoTime() - start) / 600.0 / 1_000_000.0
                    assertTrue(lab.c.aliveList.size <= CellLabModel.MAX_CELLS, "$key: cells=${lab.c.aliveList.size}")
                    assertTrue(lab.world.substancesEntity.aliveList.size <= CellLabModel.MAX_FOOD, key)
                    for (i in 0..lab.p.lastId) if (lab.p.isAlive[i]) {
                        assertTrue(lab.p.x[i].isFinite() && lab.p.y[i].isFinite(), "$key particle=$i")
                    }
                }
                times.sort()
                println("LAB_CPU $key median_ms=${times[2]} min_ms=${times[0]} max_ms=${times[4]} cells=${lab.c.aliveList.size}")
            } finally { lab.dispose() }
        }
    }

    @Test fun `сброс сохраняет настройки но начинает сцену заново`() {
        val session = CellLabSession()
        try {
            session.open("eye")
            session.apply(CellLabInput("set", "channels", 2f))
            session.apply(CellLabInput("set", "angle", 25f))
            session.apply(CellLabInput("pause", value = 1f))
            session.model!!.step()
            val old = session.model
            session.apply(CellLabInput("reset"))
            assertNotSame(old, session.model)
            assertEquals(0, session.model!!.tick)
            assertEquals(2f, session.model!!.value("channels"))
            assertEquals(25f, session.model!!.value("angle"))
            assertTrue(session.model!!.paused)
        } finally { session.dispose() }
    }

    @Test fun `контроллер управляет хвостом по нейронной связи`() {
        val lab = CellLabModel("controller")
        try {
            for ((i, name) in CellLabModel.KEYS.withIndex()) {
                lab.apply(CellLabInput("set", "key", i.toFloat()))
                val key = io.github.some_example_name.old.systems.simulation.ControllerKeys.indexOf(name)
                lab.apply(CellLabInput("keyboard", value = (1 shl key).toFloat()))
                repeat(5) { lab.step() }
                assertEquals(1f, lab.c.neuronImpulseOutput[lab.primary], name)
                assertEquals(1f, lab.c.neuronImpulseOutput[lab.companion], name)
                lab.apply(CellLabInput("keyboard", value = 0f))
                repeat(5) { lab.step() }
                assertEquals(0f, lab.c.neuronImpulseOutput[lab.companion], name)
            }
            lab.apply(CellLabInput("set", "pressed", 1f))
            repeat(5) { lab.step() }
            assertEquals(1f, lab.c.neuronImpulseOutput[lab.companion])
        } finally { lab.dispose() }
    }

    @Test fun `еда и цели появляются вдали а Pumper получает нового донора после гибели`() {
        for (key in listOf("sucker", "punisher")) {
            val lab = CellLabModel(key)
            try {
                lab.apply(CellLabInput("set", "feed", 0f))
                repeat(180) { lab.step() }
                assertEquals(0, lab.spawned)
                repeat(3) { lab.apply(CellLabInput("spawn")) }
                assertEquals(3, lab.spawned)
                val organ = lab.c.organIndex[lab.primary]
                for (i in 0..lab.p.lastId) if (lab.p.isAlive[i]) {
                    if (lab.p.isCell[i] && lab.c.organIndex[lab.p.holderEntityIndex[i]] == organ) continue
                    val dx = lab.p.x[i] - lab.c.getX(lab.primary)
                    val dy = lab.p.y[i] - lab.c.getY(lab.primary)
                    assertTrue(dx * dx + dy * dy >= 4f, "$key point=$i")
                    if (key == "punisher") assertEquals("fat", lab.world.cellList[lab.c.cellType[lab.p.holderEntityIndex[i]].toInt()].key)
                }
            } finally { lab.dispose() }
        }
        val lab = CellLabModel("pumper")
        try {
            val donor = lab.companion
            val energy = lab.c.energy[donor]
            val stored = lab.c.energy[lab.source]
            assertFalse(lab.canSpawn)
            lab.apply(CellLabInput("spawn"))
            assertEquals(1, lab.spawned)
            lab.apply(CellLabInput("grab", x = lab.c.getX(donor), y = lab.c.getY(donor)))
            lab.apply(CellLabInput("drag", x = lab.c.getX(lab.primary), y = lab.c.getY(lab.primary)))
            repeat(20) { lab.step() }
            assertTrue(lab.c.energy[donor] < energy)
            assertTrue(lab.c.energy[lab.source] > stored)
            repeat(600) {
                // Следуем за движущимся Pumper, удерживая донора в контакте.
                lab.apply(CellLabInput("drag", x = lab.c.getX(lab.primary), y = lab.c.getY(lab.primary)))
                lab.step()
            }
            assertFalse(lab.c.isAlive[donor])
            assertTrue(lab.canSpawn)
            lab.apply(CellLabInput("spawn"))
            assertEquals(2, lab.spawned)
            assertTrue(lab.c.isAlive[lab.companion])
            assertEquals(1.5f, lab.c.energy[lab.companion])
        } finally { lab.dispose() }
    }

    @Test fun `Sucker ест подведённую еду а Punisher убивает подведённую цель`() {
        for (key in listOf("sucker", "punisher")) {
            val lab = CellLabModel(key)
            try {
                lab.apply(CellLabInput("set", "feed", 0f))
                repeat(180) { lab.step() }
                lab.apply(CellLabInput("spawn"))
                val target = lab.p.aliveList.getInt(lab.p.aliveList.size - 1)
                val energy = lab.c.energy[lab.primary]
                lab.apply(CellLabInput("grab", x = lab.p.x[target], y = lab.p.y[target]))
                lab.apply(CellLabInput("drag", x = lab.c.getX(lab.primary), y = lab.c.getY(lab.primary)))
                repeat(300) { lab.step() }
                if (key == "sucker") {
                    assertEquals(0, lab.world.substancesEntity.aliveList.size)
                    assertTrue(lab.c.energy[lab.primary] > energy)
                } else assertTrue(lab.kills >= 1)
            } finally { lab.dispose() }
        }
    }

    @Test fun `Pumper только переносит энергию а смерть проверяет CellSystem`() {
        val lab = CellLabModel("pumper")
        try {
            val c = lab.c
            val donor = lab.companion
            val pumper = lab.world.cellList[c.cellType[lab.primary].toInt()]
            c.energy[lab.primary] = c.maxEnergy[lab.primary] - 0.01f
            c.energy[donor] = 0.02f
            val total = c.energy[lab.primary] + c.energy[donor]
            pumper.onContact(lab.primary, c.getParticleIndex(donor), 0.9f, 0)
            assertEquals(c.maxEnergy[lab.primary], c.energy[lab.primary])
            assertEquals(total, c.energy[lab.primary] + c.energy[donor], 0.000001f)
            assertTrue(c.energy[donor] > 0f)
            c.energy[lab.primary] = 0f
            val remainder = c.energy[donor]
            pumper.onContact(lab.primary, c.getParticleIndex(donor), 0.9f, 0)
            val rate = lab.world.substrateSettings.data.rateOfEnergyTransferForPumper
            assertEquals(rate, c.energy[lab.primary])
            assertEquals(remainder - rate, c.energy[donor])
            assertTrue(c.energy[donor] < 0f)
            assertEquals(remainder, c.energy[lab.primary] + c.energy[donor], 0.000001f)
            // Ещё один контакт до стадии клеток не снимает новую порцию.
            pumper.onContact(lab.primary, c.getParticleIndex(donor), 0.9f, 0)
            assertEquals(rate, c.energy[lab.primary])
            assertEquals(remainder - rate, c.energy[donor])
            lab.world.worldCommandsManager.executingCommandsFromTheWorld()
            assertTrue(c.isAlive[donor])
            lab.world.cellSystem.processCell(donor)
            lab.world.worldCommandsManager.executingCommandsFromTheWorld()
            assertFalse(c.isAlive[donor])
        } finally { lab.dispose() }
    }

    @Test fun `Pumper истощает нулевой запас только когда у него есть место`() {
        val lab = CellLabModel("pumper")
        try {
            val c = lab.c
            val donor = lab.companion
            val pumper = lab.world.cellList[c.cellType[lab.primary].toInt()]
            c.energy[donor] = 0f
            c.energy[lab.primary] = c.maxEnergy[lab.primary]
            pumper.onContact(lab.primary, c.getParticleIndex(donor), 0.9f, 0)
            assertEquals(0f, c.energy[donor])
            lab.world.cellSystem.processCell(donor)
            lab.world.worldCommandsManager.executingCommandsFromTheWorld()
            assertTrue(c.isAlive[donor])
            c.energy[lab.primary] = 0f
            pumper.onContact(lab.primary, c.getParticleIndex(donor), 0.9f, 0)
            assertTrue(c.energy[donor] < 0f)
            assertEquals(0f, c.energy[lab.primary] + c.energy[donor])
            lab.world.cellSystem.processCell(donor)
            lab.world.worldCommandsManager.executingCommandsFromTheWorld()
            assertFalse(c.isAlive[donor])
        } finally { lab.dispose() }
    }

    @Test fun `направление Tail сохраняется после стадии связей`() {
        val lab = CellLabModel("tail")
        try {
            lab.apply(CellLabInput("set", "angle", 90f))
            repeat(20) { lab.step() }
            assertEquals(0f, lab.c.angleCos[lab.primary], 0.01f)
            assertEquals(1f, lab.c.angleSin[lab.primary], 0.01f)
            lab.apply(CellLabInput("set", "angle", -90f))
            repeat(20) { lab.step() }
            assertEquals(0f, lab.c.angleCos[lab.primary], 0.01f)
            assertEquals(-1f, lab.c.angleSin[lab.primary], 0.01f)
        } finally { lab.dispose() }
    }

    @Test fun `тяга подходит к указателю без сильного перелёта`() {
        val lab = CellLabModel("suction_cup")
        try {
            lab.apply(CellLabInput("grab", x = 4f, y = 4f))
            lab.apply(CellLabInput("drag", x = 5f, y = 4f))
            var maxX = 4f
            repeat(180) {
                lab.step()
                maxX = maxOf(maxX, lab.c.getX(lab.primary))
            }
            assertTrue(maxX <= 5.1f, "maxX=$maxX")
            assertEquals(5f, lab.c.getX(lab.primary), 0.15f)
        } finally { lab.dispose() }
    }

    @Test fun `кость защищена а сенсор не видит чужой феромон`() {
        val punisher = CellLabModel("punisher")
        try {
            punisher.apply(CellLabInput("set", "prey", 1f))
            repeat(1200) { punisher.step() }
            assertEquals(0, punisher.kills)
            assertTrue(punisher.spawned >= 4)
        } finally { punisher.dispose() }
        val sensor = CellLabModel("pheromone_sensor")
        try {
            sensor.apply(CellLabInput("set", "sensorType", 1f))
            repeat(1200) { sensor.step() }
            assertEquals(0f, sensor.c.neuronImpulseOutput[sensor.primary])
            sensor.apply(CellLabInput("set", "sensorType", 0f))
            repeat(4) { sensor.step() }
            assertTrue(sensor.c.neuronImpulseOutput[sensor.primary] > 0f)
        } finally { sensor.dispose() }
    }
}
