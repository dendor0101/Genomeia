package io.github.some_example_name.old.core.concurrent

interface CommandQueue<T> {
    fun push(cmd: T)
    fun poll(): T?
    fun clear()
}

// ConcurrentFactory.kt
interface ConcurrentFactory {
    fun <T> createCommandQueue(): CommandQueue<T>
}
