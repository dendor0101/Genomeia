package io.github.some_example_name.old.systems.render.components

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.GL30
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.BufferUtils
import io.github.some_example_name.old.systems.pheromone.PheromonesManager.Companion.K
import io.github.some_example_name.old.systems.pheromone.PheromonesManager.Companion.P
import io.github.some_example_name.old.systems.render.RenderSystem.Companion.INITIAL_PHEROMONE_CAPACITY
import java.nio.ByteBuffer

/**
 * Renders pheromones using GLES 3.0 / WebGL2 instanced drawing.
 *
 * Instance data is uploaded as an RGBA32F texture — 2 texels per pheromone (32 bytes):
 *   texel0: x, y, A, colorR
 *   texel1: colorG, colorB, pad, pad
 *
 * Float textures (not RGBA32UI) are required for WebGL/TeaVM compatibility.
 */
class PheromoneRenderer : RenderComponent {

    private lateinit var shader: ShaderProgram
    private lateinit var mesh: Mesh

    /** GPU data texture id (RGBA32F, NEAREST). */
    private var dataTexture = 0

    /** Fixed texture width in texels (must be even — 2 texels per instance). */
    private var texWidth = TEX_WIDTH

    /** Current allocated texture height in texels. */
    private var texHeight = 0

    /** Max pheromone instances the texture can hold. */
    private var texCapacity = 0

    override fun create() {
        val vert = Gdx.files.internal("shaders/pheromone/pheromone_pc.vert").readString()
        val frag = Gdx.files.internal("shaders/pheromone/pheromone.frag").readString()
        shader = ShaderProgram(vert, frag)
        if (!shader.isCompiled) throw RuntimeException("Pheromone shader failed: ${shader.log}")

        val attributes = VertexAttributes(
            VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position")
        )

        val vertices = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )

        mesh = Mesh(false, 4, 0, attributes).apply { setVertices(vertices) }

        val buf = BufferUtils.newIntBuffer(1)
        Gdx.gl.glGenTextures(1, buf)
        dataTexture = buf.get(0)

        ensureTextureCapacity(INITIAL_PHEROMONE_CAPACITY)
    }

    /**
     * Grow RGBA32F texture so it can hold at least [neededInstances] pheromones.
     * Layout: row-major, [texWidth] texels wide (even), height grows by 1.5x.
     */
    private fun ensureTextureCapacity(neededInstances: Int) {
        if (neededInstances <= texCapacity) return

        var newCapacity = if (texCapacity == 0) INITIAL_PHEROMONE_CAPACITY else texCapacity
        while (newCapacity < neededInstances) {
            newCapacity = (newCapacity * 1.5).toInt().coerceAtLeast(neededInstances)
        }

        val instancesPerRow = texWidth / TEXELS_PER_INSTANCE
        val newHeight = (newCapacity + instancesPerRow - 1) / instancesPerRow
        newCapacity = newHeight * instancesPerRow

        val gl = Gdx.gl
        gl.glBindTexture(GL20.GL_TEXTURE_2D, dataTexture)

        // RGBA32F — WebGL2 / TeaVM compatible (FLOAT + Float32Array)
        gl.glTexImage2D(
            GL20.GL_TEXTURE_2D,
            0,
            GL30.GL_RGBA32F,
            texWidth,
            newHeight,
            0,
            GL20.GL_RGBA,
            GL20.GL_FLOAT,
            null
        )

        gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MIN_FILTER, GL20.GL_NEAREST)
        gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MAG_FILTER, GL20.GL_NEAREST)
        gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_S, GL20.GL_CLAMP_TO_EDGE)
        gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_T, GL20.GL_CLAMP_TO_EDGE)

        gl.glBindTexture(GL20.GL_TEXTURE_2D, 0)

        texHeight = newHeight
        texCapacity = newCapacity
    }

    /**
     * Upload tightly-packed pheromone ByteBuffer into the data texture.
     *
     * CPU layout per instance (32 bytes = 2 RGBA32F texels), native endian:
     *   [0..3]   float x
     *   [4..7]   float y
     *   [8..11]  float A
     *   [12..15] float colorR
     *   [16..19] float colorG
     *   [20..23] float colorB
     *   [24..31] float pad, pad
     *
     * Uses asFloatBuffer() so WebGL receives a Float32Array (required for GL_FLOAT).
     */
    private fun uploadPheromoneTexture(data: ByteBuffer, numInstances: Int) {
        ensureTextureCapacity(numInstances)

        val gl = Gdx.gl
        gl.glBindTexture(GL20.GL_TEXTURE_2D, dataTexture)

        val w = texWidth
        val totalTexels = numInstances * TEXELS_PER_INSTANCE
        val fullRows = totalTexels / w
        val remainder = totalTexels % w
        val bytesPerTexel = 16

        val oldPos = data.position()
        val oldLimit = data.limit()

        if (fullRows > 0) {
            val fullBytes = fullRows * w * bytesPerTexel
            data.position(0)
            data.limit(fullBytes)
            val floatView = data.asFloatBuffer()
            gl.glTexSubImage2D(
                GL20.GL_TEXTURE_2D,
                0,
                0, 0,
                w, fullRows,
                GL20.GL_RGBA,
                GL20.GL_FLOAT,
                floatView
            )
        }

        if (remainder > 0) {
            val offsetBytes = fullRows * w * bytesPerTexel
            data.position(offsetBytes)
            data.limit(offsetBytes + remainder * bytesPerTexel)
            val floatView = data.asFloatBuffer()
            gl.glTexSubImage2D(
                GL20.GL_TEXTURE_2D,
                0,
                0, fullRows,
                remainder, 1,
                GL20.GL_RGBA,
                GL20.GL_FLOAT,
                floatView
            )
        }

        data.position(oldPos)
        data.limit(oldLimit)

        gl.glBindTexture(GL20.GL_TEXTURE_2D, 0)
    }

    override fun resize(width: Int, height: Int) {
        // No size-dependent resources
    }

    override fun render(context: RenderContext) {
        if (!context.usePostProcess) return

        val pheromoneData = context.pheromoneData ?: return
        val numInstances = context.numPheromoneInstances
        if (numInstances == 0) return

        val blurFbo = context.blurFbo ?: return
        val gl30 = Gdx.gl30 ?: return

        uploadPheromoneTexture(pheromoneData, numInstances)

        // Render pheromones into blurFbo (vignette will read from it)
        blurFbo.begin()

        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)

        shader.bind()
        // camera.projection + camera-relative packed positions → no float stair-stepping
        shader.setUniformMatrix("u_projTrans", context.cameraRelativeProjection)
        shader.setUniformf("u_K", K)
        shader.setUniformf("u_P", P)
        shader.setUniformi("u_data", 0)
        shader.setUniformi("u_texWidth", texWidth)

        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, dataTexture)

        mesh.bind(shader)
        gl30.glDrawArraysInstanced(GL20.GL_TRIANGLE_STRIP, 0, 4, numInstances)
        mesh.unbind(shader)

        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, 0)
        Gdx.gl.glDisable(GL20.GL_BLEND)

        blurFbo.end()

        // Update texture for next component (VignetteRenderer)
        context.currentTexture = blurFbo.colorBufferTexture
    }

    override fun dispose() {
        if (::shader.isInitialized) shader.dispose()
        if (::mesh.isInitialized) mesh.dispose()

        if (dataTexture != 0) {
            val buf = BufferUtils.newIntBuffer(1).apply {
                put(dataTexture)
                flip()
            }
            Gdx.gl.glDeleteTextures(1, buf)
            dataTexture = 0
        }

        texHeight = 0
        texCapacity = 0
    }

    companion object {
        /** Texels per pheromone instance (2 × RGBA32F = 32 bytes). */
        private const val TEXELS_PER_INSTANCE = 2

        /**
         * Texture width in texels — must be divisible by TEXELS_PER_INSTANCE.
         * Stays well under GLES3 / WebGL2 minimum max-texture-size (≥ 2048).
         */
        private const val TEX_WIDTH = 1024
    }
}
