package io.github.some_example_name.render.components

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.GL30
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.BufferUtils
import io.github.some_example_name.render.pack.CellInstanceBuffer.Companion.INITIAL_CAPACITY
import java.nio.ByteBuffer


/**
 * Renders particles using GLES 3.0 instanced drawing.
 *
 * Instance data is uploaded as an RGBA32UI texture — 2 texels per particle (32 bytes):
 *   texel0: x_bits, y_bits, color, packed1
 *   texel1: packed2, pad, pad, pad
 *
 * CPU packing (RenderSystem) must match PARTICLE_STRUCT_SIZE = 32.
 * Compatible with OpenGL ES 3.0 / mobile (no SSBO).
 */
class ParticleRenderer(private val texturePaths: List<String>) : RenderComponent {

    private lateinit var shader: ShaderProgram
    private var textureArray: Int = 0
    private var numLayers: Int = 0

    /** GPU instance-data texture (RGBA32UI, NEAREST). */
    private var dataTexture = 0

    /** Texture width in texels (must be even — 2 texels per particle). */
    private var texWidth = TEX_WIDTH

    /** Allocated texture height in texels. */
    private var texHeight = 0

    /** Max particles the texture can hold. */
    private var texCapacityParticles = 0

    override fun create() {
        createShader()
        createTextureArray()
        createDataTexture()
    }

    private fun createShader() {
        val vertexShader = Gdx.files.internal("shaders/debug/circle_pc.vert").readString()
        val fragmentShader = Gdx.files.internal("shaders/debug/circle.frag").readString()
        shader = ShaderProgram(vertexShader, fragmentShader)
        if (!shader.isCompiled) {
            throw RuntimeException("Shader compilation failed: ${shader.log}")
        }
    }

    private fun createTextureArray() {
        numLayers = texturePaths.size
        if (numLayers == 0) throw IllegalStateException("Нет текстур для TextureArray!")

        val gl30 = Gdx.gl30
            ?: throw IllegalStateException("GL30 required for ParticleRenderer texture array")

        val pixmaps = texturePaths.map { path ->
            val file = Gdx.files.internal(path)
            if (!file.exists()) throw IllegalArgumentException("Текстура не найдена: $path")
            Pixmap(file)
        }

        val width = pixmaps[0].width
        val height = pixmaps[0].height

        for (p in pixmaps) {
            if (p.width != width || p.height != height) {
                throw IllegalStateException(
                    "Все текстуры в TextureArray должны быть одного размера! (${width}×${height})"
                )
            }
        }

        val buffer = BufferUtils.newIntBuffer(1)
        gl30.glGenTextures(1, buffer)
        textureArray = buffer.get(0)

        gl30.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, textureArray)

        gl30.glTexImage3D(
            GL30.GL_TEXTURE_2D_ARRAY, 0, GL30.GL_RGBA8,
            width, height, numLayers, 0,
            GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE, null
        )

        for ((layer, pixmap) in pixmaps.withIndex()) {
            gl30.glTexSubImage3D(
                GL30.GL_TEXTURE_2D_ARRAY, 0,
                0, 0, layer,
                pixmap.width, pixmap.height, 1,
                GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE,
                pixmap.pixels
            )
            pixmap.dispose()
        }

        Gdx.gl.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY)

        gl30.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_MIN_FILTER, GL30.GL_LINEAR_MIPMAP_LINEAR)
        gl30.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_MAG_FILTER, GL30.GL_LINEAR)
        gl30.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_WRAP_S, GL30.GL_REPEAT)
        gl30.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_WRAP_T, GL30.GL_REPEAT)

        gl30.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0)

        println("✅ TextureArray создан: $numLayers слоёв, ${width}×${height} px")
    }

    private fun createDataTexture() {
        val buf = BufferUtils.newIntBuffer(1)
        Gdx.gl.glGenTextures(1, buf)
        dataTexture = buf.get(0)
        ensureTextureCapacity(INITIAL_CAPACITY)
    }

    /**
     * Grow RGBA32UI texture to hold at least [neededParticles] instances.
     * Each particle = 2 texels. Width fixed (even), height grows ×1.5.
     */
    private fun ensureTextureCapacity(neededParticles: Int) {
        if (neededParticles <= texCapacityParticles) return

        var newCap = if (texCapacityParticles == 0) INITIAL_CAPACITY else texCapacityParticles
        while (newCap < neededParticles) {
            newCap = (newCap * 1.5).toInt().coerceAtLeast(neededParticles)
        }

        // particlesPerRow = texWidth / 2 (two texels each)
        val particlesPerRow = texWidth / TEXELS_PER_PARTICLE
        val newHeight = (newCap + particlesPerRow - 1) / particlesPerRow
        newCap = newHeight * particlesPerRow

        val gl = Gdx.gl
        val gl30 = Gdx.gl30
        gl.glBindTexture(GL20.GL_TEXTURE_2D, dataTexture)

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

        gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MIN_FILTER, GL20.GL_NEAREST)
        gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MAG_FILTER, GL20.GL_NEAREST)
        gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_S, GL20.GL_CLAMP_TO_EDGE)
        gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_T, GL20.GL_CLAMP_TO_EDGE)

        if (gl30 != null) {
            gl30.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_BASE_LEVEL, 0)
            gl30.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, 0)
        }

        gl.glBindTexture(GL20.GL_TEXTURE_2D, 0)

        texHeight = newHeight
        texCapacityParticles = newCap
    }

    /**
     * Upload tightly-packed particle ByteBuffer into the data texture.
     *
     * CPU layout per particle (32 bytes = 2 RGBA32UI texels), native endian:
     *   [0..3]   float x
     *   [4..7]   float y
     *   [8..11]  int color
     *   [12..15] int packed1
     *   [16..19] int packed2
     *   [20..31] pad (3× int 0)
     *
     * Zero intermediate allocations: full rows + optional partial row.
     */
    private fun uploadParticleTexture(data: ByteBuffer, numParticles: Int) {
        ensureTextureCapacity(numParticles)

        val gl = Gdx.gl
        gl.glBindTexture(GL20.GL_TEXTURE_2D, dataTexture)

        val w = texWidth
        // Each particle occupies TEXELS_PER_PARTICLE consecutive texels
        val totalTexels = numParticles * TEXELS_PER_PARTICLE
        val fullRows = totalTexels / w
        val remainderTexels = totalTexels % w
        val bytesPerTexel = 16 // RGBA32UI

        val oldPos = data.position()
        val oldLimit = data.limit()

        if (fullRows > 0) {
            val fullBytes = fullRows * w * bytesPerTexel
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

        if (remainderTexels > 0) {
            val offsetBytes = fullRows * w * bytesPerTexel
            data.position(offsetBytes)
            data.limit(offsetBytes + remainderTexels * bytesPerTexel)
            gl.glTexSubImage2D(
                GL20.GL_TEXTURE_2D,
                0,
                0, fullRows,
                remainderTexels, 1,
                GL30.GL_RGBA_INTEGER,
                GL20.GL_UNSIGNED_INT,
                data
            )
        }

        data.position(oldPos)
        data.limit(oldLimit)

        gl.glBindTexture(GL20.GL_TEXTURE_2D, 0)
    }

    override fun resize(width: Int, height: Int) {
        // ParticleRenderer doesn't have size-dependent resources
    }

    override fun render(context: RenderContext) {
        val gl30 = Gdx.gl30 ?: return
        val particleData = context.particleData
        val dataSize = particleData?.remaining() ?: 0
        val numInstances = context.numInstances

        if (context.isNewFrame && particleData != null && dataSize > 0 && numInstances > 0) {
            uploadParticleTexture(particleData, numInstances)
        }

        if (context.usePostProcess) {
            context.sceneFbo?.begin()
        }

        Gdx.gl.glDisable(GL20.GL_BLEND)
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
        Gdx.gl.glDepthFunc(GL20.GL_LESS)
        Gdx.gl.glDepthMask(true)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

        shader.bind()
        shader.setUniformMatrix("u_projTrans", context.cameraProjection)
        shader.setUniformf("u_textureScale", 1.0f)
        shader.setUniformf("u_colorScale", if (context.usePostProcess) 0.0f else 1.0f)
        shader.setUniformi("u_textureArray", 0)
        shader.setUniformi("u_data", 1)
        shader.setUniformi("u_texWidth", texWidth)

        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)
        gl30.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, textureArray)

        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE1)
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, dataTexture)

        context.fullscreenMesh.bind(shader)
        gl30.glDrawArraysInstanced(GL20.GL_TRIANGLE_STRIP, 0, 4, numInstances)
        context.fullscreenMesh.unbind(shader)

        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE1)
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, 0)
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)
        gl30.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0)

        if (context.usePostProcess) {
            context.sceneFbo?.end()
            context.currentTexture = context.sceneFbo?.colorBufferTexture
        }
    }

    override fun dispose() {
        if (::shader.isInitialized) shader.dispose()

        val gl = Gdx.gl
        if (textureArray != 0) {
            val deleteBuf = BufferUtils.newIntBuffer(1).apply {
                put(textureArray)
                flip()
            }
            gl.glDeleteTextures(1, deleteBuf)
            textureArray = 0
        }

        if (dataTexture != 0) {
            val deleteBuf = BufferUtils.newIntBuffer(1).apply {
                put(dataTexture)
                flip()
            }
            gl.glDeleteTextures(1, deleteBuf)
            dataTexture = 0
        }

        texHeight = 0
        texCapacityParticles = 0
    }

    companion object {
        /** Texels per particle in the data texture (2 × RGBA32UI = 32 bytes). */
        private const val TEXELS_PER_PARTICLE = 2

        /**
         * Texture width in texels — must be divisible by TEXELS_PER_PARTICLE
         * so a particle never straddles a row boundary.
         */
        private const val TEX_WIDTH = 1024
    }
}
