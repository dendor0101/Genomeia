package io.github.some_example_name.render.components

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.GL30
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.BufferUtils
import io.github.some_example_name.render.pack.PheromoneInstanceBuffer.Companion.INITIAL_CAPACITY
import io.github.some_example_name.render.pack.PheromoneInstanceBuffer.Companion.STRUCT_SIZE
import java.nio.ByteBuffer

/**
 * Renders pheromones using GLES 3.0 instanced drawing.
 *
 * Instance data is uploaded as an RGBA32UI texture (one texel per pheromone).
 * CPU packing is unchanged from the SSBO path (16 bytes / instance):
 *   putFloat(x), putFloat(y), putFloat(A), putInt(color)
 *
 * On the GPU those 4×uint bits are recovered via uintBitsToFloat / unpackUnorm4x8.
 * Integer textures avoid float NaN-canonicalization that would corrupt color bits.
 *
 * Compatible with OpenGL ES 3.0 / mobile (no SSBO).
 */
class PheromoneRenderer : RenderComponent {

    private lateinit var shader: ShaderProgram
    private lateinit var mesh: Mesh

    /** GPU data texture id (RGBA32UI, NEAREST). */
    private var dataTexture = 0

    /** Fixed texture width in texels. Height grows with capacity. */
    private var texWidth = TEX_WIDTH

    /** Current allocated texture height in texels. */
    private var texHeight = 0

    /** Max pheromone instances the texture can hold (texWidth * texHeight). */
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

        ensureTextureCapacity(INITIAL_CAPACITY)
    }

    /**
     * Grow RGBA32UI texture so it can hold at least [neededInstances] pheromones.
     * Layout: row-major, [texWidth] texels wide, height grows by 1.5x.
     * Zero GPU reallocations on the steady-state path.
     */
    private fun ensureTextureCapacity(neededInstances: Int) {
        if (neededInstances <= texCapacity) return

        var newCapacity = if (texCapacity == 0) INITIAL_CAPACITY else texCapacity
        while (newCapacity < neededInstances) {
            newCapacity = (newCapacity * 1.5).toInt().coerceAtLeast(neededInstances)
        }

        // Round up to full rows so every texel slot is addressable
        val newHeight = (newCapacity + texWidth - 1) / texWidth
        newCapacity = newHeight * texWidth

        val gl = Gdx.gl
        val gl30 = Gdx.gl30
        gl.glBindTexture(GL20.GL_TEXTURE_2D, dataTexture)

        // RGBA32UI — exact bit-preserving storage (no float NaN scrubbing)
        gl.glTexImage2D(
            GL20.GL_TEXTURE_2D,
            0,
            GL30.GL_RGBA32UI,
            texWidth,
            newHeight,
            0,
            GL30.GL_RGBA_INTEGER,
            GL20.GL_UNSIGNED_INT,
            null
        )

        // NEAREST — exact texelFetch, no filtering between instances
        gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MIN_FILTER, GL20.GL_NEAREST)
        gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MAG_FILTER, GL20.GL_NEAREST)
        gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_S, GL20.GL_CLAMP_TO_EDGE)
        gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_T, GL20.GL_CLAMP_TO_EDGE)

        // Integer textures must not have non-zero base level / incomplete mip chain
        if (gl30 != null) {
            gl30.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_BASE_LEVEL, 0)
            gl30.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, 0)
        }

        gl.glBindTexture(GL20.GL_TEXTURE_2D, 0)

        texHeight = newHeight
        texCapacity = newCapacity
    }

    /**
     * Upload tightly-packed pheromone ByteBuffer into the data texture.
     *
     * CPU layout per instance (16 bytes = 1 RGBA32UI texel), native endian:
     *   [0..3]   float x     → bits read as uint, uintBitsToFloat on GPU
     *   [4..7]   float y     → same
     *   [8..11]  float A     → same
     *   [12..15] int color   → RGBA8 packed uint, unpackUnorm4x8 on GPU
     *
     * Upload strategy (zero intermediate allocations):
     *   1) full rows as one glTexSubImage2D
     *   2) leftover partial row as a second glTexSubImage2D
     *
     * Row pitch is always a multiple of 16 bytes → default UNPACK_ALIGNMENT=4 is fine.
     */
    private fun uploadPheromoneTexture(data: ByteBuffer, numInstances: Int) {
        ensureTextureCapacity(numInstances)

        val gl = Gdx.gl
        gl.glBindTexture(GL20.GL_TEXTURE_2D, dataTexture)

        val w = texWidth
        val fullRows = numInstances / w
        val remainder = numInstances % w
        val bytesPerTexel = STRUCT_SIZE // 16

        // Save caller buffer state
        val oldPos = data.position()
        val oldLimit = data.limit()

        if (fullRows > 0) {
            val fullBytes = fullRows * w * bytesPerTexel
            // Buffer must start at absolute offset 0 of the packed array
            data.position(0)
            data.limit(fullBytes)
            gl.glTexSubImage2D(
                GL20.GL_TEXTURE_2D,
                0,
                0, 0,
                w, fullRows,
                GL30.GL_RGBA_INTEGER,
                GL20.GL_UNSIGNED_INT,
                data
            )
        }

        if (remainder > 0) {
            val offsetBytes = fullRows * w * bytesPerTexel
            data.position(offsetBytes)
            data.limit(offsetBytes + remainder * bytesPerTexel)
            gl.glTexSubImage2D(
                GL20.GL_TEXTURE_2D,
                0,
                0, fullRows,          // x=0, y=first incomplete row
                remainder, 1,         // width=remainder texels, height=1
                GL30.GL_RGBA_INTEGER,
                GL20.GL_UNSIGNED_INT,
                data
            )
        }

        // Restore buffer state for any subsequent readers
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
        shader.setUniformMatrix("u_projTrans", context.cameraProjection)
        shader.setUniformf("u_K", context.pheromoneK)
        shader.setUniformf("u_P", context.pheromoneP)
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
        /**
         * Texture width in texels. 1024 keeps addressing simple and stays
         * well under GLES3 minimum max-texture-size (≥ 2048).
         * Height grows as capacity increases.
         *
         * 1024 × 1 row = 1024 instances ≈ INITIAL capacity ballpark after grow.
         */
        private const val TEX_WIDTH = 1024
    }
}
