package io.github.some_example_name.old

import java.util.concurrent.RecursiveTask
import kotlin.math.abs
import kotlin.math.hypot

class SumTask(private val array: DoubleArray, private val start: Int, private val end: Int) : RecursiveTask<Double >() {
    companion object {
        private const val THRESHOLD = 1500
    }

    override fun compute(): Double  {
        return if (end - start <= THRESHOLD) {
            var result: Double  = 0.0
            for (i in start until end) {
                result += hypot(array[i], abs(array[i] + 153f))
            }
            result
        } else {
            val mid = (start + end) / 2
            val leftTask = SumTask(array, start, mid)
            val rightTask = SumTask(array, mid, end)
            leftTask.fork()
            val rightResult = rightTask.compute()
            val leftResult = leftTask.join()
            leftResult + rightResult
        }
    }
}

fun main() {
//    val array = DoubleArray(1_000_000_000) { 131.0 }
//    val pool = ForkJoinPool()
//
//    // Параллельное вычисление
//    val parallelTime = measureTimeMillis {
//        val task = SumTask(array, 0, array.size)
//        val parallelSum = pool.invoke(task)
//        println("Parallel sum: $parallelSum")
//    }
//    println("Parallel execution time: $parallelTime ms")

    var x = 35.98
    val vx = 0.3

    // Сохраняем целую часть до прибавления
    val initialIntPart = x.toInt()

    // Прибавляем vx к x
    x += vx

    // Получаем новую целую часть
    val newIntPart = x.toInt()

    // Проверяем, изменилась ли целая часть
    if (initialIntPart != newIntPart) {
        println("Целая часть изменилась: $initialIntPart -> $newIntPart")
    } else {
        println("Целая часть не изменилась")
    }
}

