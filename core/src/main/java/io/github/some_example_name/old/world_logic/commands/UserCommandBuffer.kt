package io.github.some_example_name.old.world_logic.commands

import kotlin.collections.forEach


// Командный буфер с двойной очередью
class UserCommandBuffer {
    private val bufferA = mutableListOf<GameCommand>()
    private val bufferB = mutableListOf<GameCommand>()

    var writeBuffer = bufferA
    var readBuffer = bufferB

    fun push(cmd: GameCommand) {
        writeBuffer.add(cmd)
    }

    inline fun swapAndConsume(consumer: (GameCommand) -> Unit) {
        // Меняем местами очереди
        val tmp = writeBuffer
        writeBuffer = readBuffer
        readBuffer = tmp

        // Теперь readBuffer содержит все команды с прошлого кадра
        if (readBuffer.isNotEmpty()) {
            readBuffer.forEach(consumer)
            readBuffer.clear()
        }
    }
}
