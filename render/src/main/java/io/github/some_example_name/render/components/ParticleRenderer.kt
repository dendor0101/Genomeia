package io.github.some_example_name.render.components

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.GL30
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.BufferUtils
import io.github.some_example_name.render.RenderMode

/**
 * Рисует клетки реалистичного режима: текстура из массива слоёв, тонированная под цвет
 * клетки. Отладочный режим рисует DebugRenderer, ностальгический — NostalgicRenderer.
 *
 * Геометрию (веер, инстансный VBO, VAO) держит [CellGeometry] — она общая с
 * NostalgicRenderer, а заливает её WorldRenderer один раз за кадр. Здесь только шейдер,
 * массив текстур и сам проход.
 */
class ParticleRenderer(
    private val texturePaths: List<String>,
    private val cells: CellGeometry
) : RenderComponent {

    private lateinit var shader: ShaderProgram
    private var textureArray: Int = 0
    private var numLayers: Int = 0

    override fun create() {
        createShader()
        createTextureArray()
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

        gl30.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY)

        gl30.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_MIN_FILTER, GL30.GL_LINEAR_MIPMAP_LINEAR)
        gl30.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_MAG_FILTER, GL30.GL_LINEAR)
        gl30.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_WRAP_S, GL30.GL_REPEAT)
        gl30.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_WRAP_T, GL30.GL_REPEAT)

        gl30.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0)

        println("✅ TextureArray создан: $numLayers слоёв, ${width}×${height} px")
    }

    override fun resize(width: Int, height: Int) {
        // ParticleRenderer doesn't have size-dependent resources
    }

    override fun render(context: RenderContext) {
        // Остальные режимы рисуют клетки своими проходами — DebugRenderer и NostalgicRenderer.
        if (context.mode != RenderMode.REALISTIC) return

        val gl = Gdx.gl
        val gl30 = Gdx.gl30 ?: return
        val sceneFbo = context.sceneFbo ?: return

        // Только в scene FBO: дальше кадр идёт через постпроцесс. Раньше здесь была и
        // ветка прямо в экран — ею рисовалась отладка, теперь у неё свой DebugRenderer.
        sceneFbo.begin()

        gl.glDisable(GL20.GL_BLEND)
        gl.glEnable(GL20.GL_DEPTH_TEST)
        gl.glDepthFunc(GL20.GL_LESS)
        gl.glDepthMask(true)
        gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

        shader.bind()
        shader.setUniformMatrix("u_projTrans", context.cameraProjection)
        shader.setUniformf("u_lag", context.interpolationLag)
        shader.setUniformi("u_textureArray", 0)

        gl.glActiveTexture(GL20.GL_TEXTURE0)
        gl30.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, textureArray)

        cells.draw(context.numInstances, CellGeometry.Detail.CONE)

        gl30.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0)

        sceneFbo.end()
        context.currentTexture = sceneFbo.colorBufferTexture
    }

    override fun dispose() {
        if (::shader.isInitialized) shader.dispose()

        if (textureArray != 0) {
            val deleteBuf = BufferUtils.newIntBuffer(1).apply {
                put(textureArray)
                flip()
            }
            Gdx.gl.glDeleteTextures(1, deleteBuf)
            textureArray = 0
        }
    }
}
