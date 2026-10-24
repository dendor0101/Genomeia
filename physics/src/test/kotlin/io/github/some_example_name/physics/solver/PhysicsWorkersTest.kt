package io.github.some_example_name.physics

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PhysicsWorkersTest {
    /**
     * Два потока на одном общем пуле — как окно стенда и фоновый прогрев копии тела.
     * До 25.10 второй run портил задание первого, и барьер висел вечно.
     */
    @Test(timeout = 20000)
    fun concurrentCallersOnOneSharedPoolFinishWithFullResults() {
        val pool = SpinPhysicsWorkers(4)
        try {
            val results = Array(2) { LongArray(64) }
            val threads = Array(2) { t ->
                Thread {
                    val out = results[t]
                    repeat(2000) { round ->
                        pool.run(out.size) { k, _ -> out[k] += (k + round).toLong() }
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            val expected = LongArray(64) { k -> (0 until 2000).sumOf { (k + it).toLong() } }
            assertArrayEquals(expected, results[0])
            assertArrayEquals(expected, results[1])
        } finally {
            pool.shutdown()
        }
    }
}
