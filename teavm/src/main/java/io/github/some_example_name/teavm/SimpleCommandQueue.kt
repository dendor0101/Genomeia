package io.github.some_example_name.teavm

import io.github.some_example_name.old.core.concurrent.CommandQueue
import io.github.some_example_name.old.core.concurrent.ConcurrentFactory
import kotlin.collections.ArrayDeque

class SimpleCommandQueue<T> : CommandQueue<T> {
    private val queue = ArrayDeque<T>()

    override fun push(cmd: T) {
        queue.addLast(cmd)
    }

    override fun poll(): T? = queue.removeFirstOrNull()
    override fun clear() {
        queue.clear()
    }
}

class WebConcurrentFactory : ConcurrentFactory {
    override fun <T> createCommandQueue(): CommandQueue<T> = SimpleCommandQueue()
}
