package io.github.some_example_name.old.systems.debug

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.cells.Zygote
import io.github.some_example_name.old.commands.PlayerCommand
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.ui.screens.GlobalSettings
import io.github.some_example_name.old.ui.screens.GlobalSettings.DEBUG_MODE
import java.io.DataOutputStream
import java.io.File
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.DISimulationContainer.simulationData
import io.github.some_example_name.old.core.utils.collectParticles
import io.github.some_example_name.old.systems.simulation.SimulationData
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class LogSaver { //TODO: Проверить в будущем что ВСЯ симуляция использует только 1 вид рандома.

    var file: File? = null
    var dataStream: DataOutputStream? = null

    private val isClosed = AtomicBoolean(false)

    fun initLog() {
        if (DEBUG_MODE) {
            file = File("debug.bin")
            dataStream = DataOutputStream(file?.outputStream())

            dataStream?.writeInt(DISimulationContainer.gridWidth)
            dataStream?.writeInt(DISimulationContainer.gridHeight)
            println("size writed")
            dataStream?.writeLong(DISimulationContainer.seed)
            println("Seed writed: ${DISimulationContainer.seed}")

            Runtime.getRuntime().addShutdownHook(Thread {
                close() // не работает чисто оставлю для галочки
            })
        }
    }

    fun saveTick() {
        dataStream?.flush()
        dataStream?.writeInt(-555) // начальный кодон тика
//        dataStream?.writeInt(simulationData.tickCounter)
        //println("tick saved")
    }

    fun saveDebug(x: Float, y: Float, tick: Int) {
        //println("debug save")
        dataStream?.writeInt(-454)
        dataStream?.writeInt(tick)
        dataStream?.writeFloat(x)
        dataStream?.writeFloat(y)
    }

    fun saveUserCommand(type: PlayerCommand) {
//        println("got data")
//        println("Command: ${type.toString()}")
        synchronized(this) {
            dataStream?.writeInt(-767)//split codon for user command
            dataStream?.writeInt(simulationData.tickCounter)

            when (type) {
                PlayerCommand.StopDrag -> {
                    //println("stop dragging")
                    dataStream?.writeInt(0) // command type

                    dataStream?.writeInt(-676) //end codon
                }

                is PlayerCommand.Drag -> {
                    //println("dragging")
                    dataStream?.writeInt(1)
                    dataStream?.writeFloat(type.x)
                    dataStream?.writeFloat(type.y)
                    dataStream?.writeFloat(type.dx)
                    dataStream?.writeFloat(type.dy)

                    dataStream?.writeInt(-676)
                }

                is PlayerCommand.Tap -> {
                    //println("tapped")
                    dataStream?.writeInt(2)

                    dataStream?.writeFloat(type.x)
                    dataStream?.writeFloat(type.y)
                    dataStream?.writeBoolean(type.isLeftButton)
                    dataStream?.writeInt(type.genomeIndex)//DISimulationContainer.simulationData.currentGenomeIndex)
                    //dataStream?.writeFloat(type.angle)

                    dataStream?.writeInt(-676)
                }

                is PlayerCommand.TouchDown -> {
                    //println("touch downed")
                    dataStream?.writeInt(3)

                    dataStream?.writeFloat(type.x)
                    dataStream?.writeFloat(type.y)
                    dataStream?.writeBoolean(type.isLeftButton)

                    dataStream?.writeInt(-676)
                }
            }
        }
    }

    fun close() {
        if (isClosed.compareAndSet(false, true)) {
            try {
                dataStream?.writeInt(-50)
                dataStream?.writeInt(simulationData.tickCounter)
                dataStream?.flush()
            }
            catch (_: Exception) {
            }
            finally {
                try {
                    dataStream?.close()
                }
                catch (_: Exception) {
                }
            }
        }
    }
}
