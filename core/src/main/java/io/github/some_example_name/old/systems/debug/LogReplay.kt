package io.github.some_example_name.old.systems.debug
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.utils.TimeUtils
import io.github.some_example_name.old.commands.PlayerCommand
import io.github.some_example_name.old.commands.WorldCommandBuffer
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.DISimulationContainer.simulationData
import io.github.some_example_name.old.core.DISimulationContainer.simulationSystem
import io.github.some_example_name.old.systems.simulation.SimulationData
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.lang.Integer.max
import java.util.Dictionary

class LogReplay {
    val file = File("debug.bin")
    val dataStream = DataInputStream(file.inputStream())
    val userCommands = mutableListOf<UserCommandRecord>()
    var currentTick = 0


    var startReplayTick = -1
    private var nextCommandIndex = 0
    var replaying = false
    var stopTick = -1
    val coordList = mutableMapOf<Int, Pair<Float, Float>>()

    fun startReplayTimer() {
        simulationData.tickCounter = 0//coordList.keys.first()
        startReplayTick = 0//DISimulationContainer.simulationData.tickCounter
        nextCommandIndex = 0
        replaying = true
        simulationData.isRestart = true
    }

    fun updateReplay() {
        if (!replaying || nextCommandIndex >= userCommands.size) return

        //val elapsedTicks = (DISimulationContainer.simulationData.tickCounter - startReplayTick)+userCommands[0].time
        val curentTick = DISimulationContainer.simulationData.tickCounter
        println("${userCommands[nextCommandIndex].time}: ${curentTick}")
        while (nextCommandIndex < userCommands.size &&
            userCommands[nextCommandIndex].time <= curentTick) {
            DISimulationContainer.userCommandManager.push(
                userCommands[nextCommandIndex].command
            )
            nextCommandIndex++
        }

        if (nextCommandIndex >= userCommands.size) {
            replaying = false
        }

        if ((currentTick >= stopTick) && stopTick != -1 || nextCommandIndex >= userCommands.size) {
            simulationData.isPlay = false
        }
    }

    fun play() {
        println("WIDTH: ${dataStream.readInt()}")
        println("HEIGHT: ${dataStream.readInt()}")

        val seed = dataStream.readLong()

        MathUtils.random.setSeed(seed)
        DISimulationContainer.userCommandManager.random.setSeed(seed)

        println("Seed ${seed} is setted")
        parse()
        startReplayTimer()
        println("start to shine!")
    }

    fun parse() {
        try {
            while (true) {
                val cmd = dataStream.readInt()
                when (cmd) {
                    -555 -> {
                        //ждем
                    }
                    -999 -> {
                        println("TICK STOP")
                    }
                    -767 -> {
                        currentTick = dataStream.readInt()
                        parseUserCommand() //команда пользователя
                    }
                    -50 -> {
                        stopTick = dataStream.readInt()
                    }
                    -454 -> {
                        val tick = dataStream.readInt()
                        val x = dataStream.readFloat()
                        val y = dataStream.readFloat()
                        coordList[tick] = Pair<Float, Float>(x, y)
                    }
                    else -> {
                        return // Останавливаемся, чтобы не читать мусор
                    }
                }
            }
        }
        catch (e: EOFException) {
            println("file end")
            DISimulationContainer.worldCommandsManager.replay = false

        }
        finally {
            dataStream.close()
        }
    }


    fun parseUserCommand() {
        val cmd = dataStream.readInt()
        var command: PlayerCommand? = null

        when (cmd) {
            0 -> {
                command = PlayerCommand.StopDrag
                dataStream.readInt() // for split
            }

            1 -> {
                val x = dataStream.readFloat()
                val y = dataStream.readFloat()
                val dx = dataStream.readFloat()
                val dy = dataStream.readFloat()
                command = PlayerCommand.Drag(x, y, dx, dy)

                dataStream.readInt()
            }

            2 -> {
                val x = dataStream.readFloat()
                val y = dataStream.readFloat()
                val isLeftButton = dataStream.readBoolean()
                val genomeIndex = dataStream.readInt()
                //val angle = dataStream.readFloat()

                command = PlayerCommand.Tap(x=x, y = y, isLeftButton = isLeftButton, genomeIndex = genomeIndex)

                dataStream.readInt()
            }

            3 -> {
                val x = dataStream.readFloat()
                val y = dataStream.readFloat()
                val isLeftButton = dataStream.readBoolean()

                command = PlayerCommand.TouchDown(x, y, isLeftButton)

                dataStream.readInt()
            }
        }

        if (command != null) {
            userCommands.add(UserCommandRecord(command, currentTick))
        }
    }
    data class UserCommandRecord(
        val command: PlayerCommand,
        val time: Int
    )

}
