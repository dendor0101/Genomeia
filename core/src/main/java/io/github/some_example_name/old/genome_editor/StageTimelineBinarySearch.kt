package io.github.some_example_name.old.genome_editor

class StageTimelineBinarySearch(private val starts: IntArray) {

    fun getStage(timeSec: Int): Int {
        // Бинарный поиск последнего элемента <= timeSec
        var low = 0
        var high = starts.lastIndex

        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = starts[mid]
            if (value == timeSec) return mid
            if (value < timeSec) low = mid + 1 else high = mid - 1
        }

        // high указывает на индекс последнего элемента < timeSec
        return if (high >= 0) high else 0
    }
}
