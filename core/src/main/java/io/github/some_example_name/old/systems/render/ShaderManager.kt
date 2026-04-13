package io.github.some_example_name.old.systems.render

import com.badlogic.gdx.math.Matrix4
import java.nio.ByteBuffer

interface ShaderManager {
    fun create()
    fun render(
        currentRead: ByteBuffer,
        cameraProjection: Matrix4,
        isNewFrame: Boolean,
        isClear: Boolean,
        worldX: Float,
        worldY: Float,
        blurAmount: Float,
        zoom: Float
    )
    fun dispose()

    fun resize(width: Int, height: Int)
}
