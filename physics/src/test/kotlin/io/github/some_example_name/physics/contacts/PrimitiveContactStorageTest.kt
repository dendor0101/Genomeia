package io.github.some_example_name.physics

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class PrimitiveContactStorageTest {
    @Test fun lambdaCacheMatchesMapThroughGrowthRemovalAndClear() {
        val cache = ContactLambdaCache()
        val reference = HashMap<Long, Double>()
        val random = Random(741)
        val keys = LongArray(4000) { if (it == 0) 0L else random.nextLong() }
        repeat(8) { round ->
            for (i in keys.indices) {
                val value = random.nextDouble() * (round + 1)
                cache[keys[i]] = value; reference[keys[i]] = value
            }
            for (i in keys.indices step 3) { cache.remove(keys[i]); reference.remove(keys[i]) }
            for (i in keys.indices step 7) { cache[keys[i]] = -0.0; reference[keys[i]] = -0.0 }
            for (key in keys) assertEquals(reference.getOrDefault(key, -1.0).toRawBits(),
                cache.getOrDefault(key, -1.0).toRawBits())
            cache.clear(); reference.clear()
            for (key in keys) assertEquals(-1.0, cache.getOrDefault(key, -1.0), 0.0)
        }
    }

    @Test fun linkedPairsKeepBothOrientationsDuplicatesAndEmptyInput() {
        val pairs = LinkedPairs.from(intArrayOf(0, 7, 3, 0), intArrayOf(3, 1, 0, 3), 4)
        assertTrue(pairs.contains(3L))
        assertTrue(pairs.contains(1000003L + 7L))
        assertFalse(pairs.contains(7L))
        assertFalse(LinkedPairs.from(IntArray(0), IntArray(0), 0).contains(0L))
    }
}
