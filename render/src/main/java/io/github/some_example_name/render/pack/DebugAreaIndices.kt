package io.github.some_example_name.render.pack

import com.badlogic.gdx.utils.BufferUtils
import java.nio.IntBuffer

/** Один слот тройного буфера. GL читает его только пока слот принадлежит читателю. */
class DebugAreaIndices {
    var source: Any? = null
        private set
    var version = 0L
        private set
    lateinit var data: IntBuffer
        private set

    fun update(owner: Any, revision: Long, indices: IntArray, count: Int) {
        if (source === owner && version == revision) return
        if (!::data.isInitialized || data.capacity() < count) {
            val capacity = if (::data.isInitialized) data.capacity() else 0
            data = BufferUtils.newIntBuffer(maxOf(count, capacity * 2))
        }
        data.clear()
        data.put(indices, 0, count)
        data.flip()
        source = owner
        version = revision
    }
}
