// ComputeTestScreen.kt
package io.github.some_example_name.old.good_one   // ← замени на свой пакет

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.GL30
import com.badlogic.gdx.graphics.GL32
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.ScreenUtils
import java.nio.IntBuffer

class ComputeTestScreen : ScreenAdapter() {

    private lateinit var batch: SpriteBatch
    private var computeProgram = 0
    private lateinit var texture: Texture
    private var textureHandle = 0
    private val width = 512
    private val height = 512

    private val intBuffer: IntBuffer = BufferUtils.newIntBuffer(1)

    override fun show() {
        batch = SpriteBatch()

        // === ИСПРАВЛЕННЫЙ СПОСОБ СОЗДАНИЯ FBO (работает во всех новых LibGDX) ===
        val fboBuilder = GLFrameBuffer.FrameBufferBuilder(width, height)
        fboBuilder.addColorTextureAttachment(GL30.GL_RGBA8, GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE)
        val fbo = fboBuilder.build()

        texture = fbo.colorBufferTexture
        textureHandle = texture.textureObjectHandle
        fbo.dispose() // нам нужен только handle

        // 2. Compute Shader (#version 320 es)
        val shaderSource = """
            #version 320 es
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(rgba8, binding = 0) uniform writeonly image2D imgOutput;

            void main() {
                ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
                if (pos.x >= 512 || pos.y >= 512) return;

                vec4 color = vec4(
                    float(pos.x) / 512.0,
                    float(pos.y) / 512.0,
                    sin(float(pos.x + pos.y) * 0.05),
                    1.0
                );
                imageStore(imgOutput, pos, color);
            }
        """.trimIndent()

        val shader = Gdx.gl32.glCreateShader(GL32.GL_COMPUTE_SHADER)
        Gdx.gl32.glShaderSource(shader, shaderSource)
        Gdx.gl32.glCompileShader(shader)

        Gdx.gl32.glGetShaderiv(shader, GL20.GL_COMPILE_STATUS, intBuffer)
        if (intBuffer[0] == GL20.GL_FALSE) {
            Gdx.app.error("Compute", "Shader error:\n${Gdx.gl32.glGetShaderInfoLog(shader)}")
        }

        computeProgram = Gdx.gl32.glCreateProgram()
        Gdx.gl32.glAttachShader(computeProgram, shader)
        Gdx.gl32.glLinkProgram(computeProgram)

        Gdx.gl32.glGetProgramiv(computeProgram, GL20.GL_LINK_STATUS, intBuffer)
        if (intBuffer[0] == GL20.GL_FALSE) {
            Gdx.app.error("Compute", "Link error:\n${Gdx.gl32.glGetProgramInfoLog(computeProgram)}")
        }

        Gdx.gl32.glDeleteShader(shader)
    }

    override fun render(delta: Float) {
        ScreenUtils.clear(0f, 0f, 0f, 1f)

        Gdx.gl32.glUseProgram(computeProgram)
        Gdx.gl32.glBindImageTexture(0, textureHandle, 0, false, 0, GL32.GL_WRITE_ONLY, GL30.GL_RGBA8)
        Gdx.gl32.glDispatchCompute(width / 16, height / 16, 1)
        Gdx.gl32.glMemoryBarrier(GL32.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)

        batch.begin()
        batch.draw(texture, 0f, 0f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        batch.end()
    }

    override fun dispose() {
        batch.dispose()
        if (computeProgram != 0) Gdx.gl32.glDeleteProgram(computeProgram)
        texture.dispose()
    }
}
