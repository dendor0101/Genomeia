package com.example.concurrent

import io.github.some_example_name.old.core.concurrent.CommandQueue
import io.github.some_example_name.old.core.concurrent.ConcurrentFactory
import java.util.concurrent.ConcurrentLinkedQueue

class ConcurrentCommandQueue<T> : CommandQueue<T> {
    private val queue = ConcurrentLinkedQueue<T>()

    override fun push(cmd: T) {
        queue.offer(cmd)
    }

    override fun poll(): T? = queue.poll()

    override fun clear() {
        queue.clear()
    }
}

class JvmConcurrentFactory : ConcurrentFactory {
    override fun <T> createCommandQueue(): CommandQueue<T> = ConcurrentCommandQueue()
}
