package io.github.some_example_name.old.systems.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.GL30
import com.badlogic.gdx.graphics.GL31
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.BufferUtils
import io.github.some_example_name.old.systems.render.RenderSystem.Companion.INITIAL_PARTICLE_CAPACITY
import io.github.some_example_name.old.systems.render.RenderSystem.Companion.PARTICLE_STRUCT_SIZE
import java.nio.ByteBuffer
import kotlin.math.sin


var usePostProcess = false

class ShaderManagerLibgdxApi : ShaderManager {
    private val ssbos = IntArray(2)
    private var currentReadIndex = 0
    private val ssboCapacities = IntArray(2)

    private lateinit var shader: ShaderProgram
    private lateinit var mesh: Mesh
    private lateinit var sobelShader: ShaderProgram

    private var textureArray: Int = 0
    private var numLayers: Int = 0

    // === НОВОЕ: пост-процессинг (FBO + лёгкий blur-шейдер) ===
    private lateinit var fbo: FrameBuffer
    private lateinit var blurShader: ShaderProgram

//    private lateinit var linesTexture: Texture

    private lateinit var blurFbo: FrameBuffer

    private val invProjMatrix = Matrix4()

    private fun createTextureArray() {
        val texturePaths = arrayOf(
            "leaf.png",
            "fat.png",
            "bone.png",//Bone
            "fat.png",//Tail
            "neuron.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "muscle.png",
            "not_cell.png"
            // добавляй сюда сколько угодно (до 64+ легко)
            // порядок = номер ex_cellType
        )

        numLayers = texturePaths.size
        if (numLayers == 0) throw IllegalStateException("Нет текстур для TextureArray!")

        // Загружаем все пискмапы
        val pixmaps = texturePaths.map { path ->
            val file = Gdx.files.internal(path)
            if (!file.exists()) throw IllegalArgumentException("Текстура не найдена: $path")
            Pixmap(file)   // PNG с альфой отлично работает
        }

        val width = pixmaps[0].width
        val height = pixmaps[0].height

        // Все текстуры должны быть одного размера
        for (p in pixmaps) {
            if (p.width != width || p.height != height) {
                throw IllegalStateException("Все текстуры в TextureArray должны быть одного размера! (${width}×${height})")
            }
        }

        // Создаём Texture Array
        val buffer = BufferUtils.newIntBuffer(1)
        Gdx.gl31.glGenTextures(1, buffer)
        textureArray = buffer.get(0)

        Gdx.gl31.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, textureArray)

        // Выделяем память (уровень 0)
        Gdx.gl31.glTexImage3D(
            GL30.GL_TEXTURE_2D_ARRAY,
            0,                          // base level
            GL30.GL_RGBA8,              // internal format
            width, height, numLayers,
            0,                          // border (всегда 0)
            GL30.GL_RGBA,
            GL30.GL_UNSIGNED_BYTE,
            null                        // только выделяем память
        )

        // Заливаем каждый слой
        for ((layer, pixmap) in pixmaps.withIndex()) {
            Gdx.gl31.glTexSubImage3D(
                GL30.GL_TEXTURE_2D_ARRAY,
                0,                          // mip level
                0, 0, layer,                // x, y, z (layer)
                pixmap.width, pixmap.height, 1,
                GL30.GL_RGBA,
                GL30.GL_UNSIGNED_BYTE,
                pixmap.getPixels()
            )
            pixmap.dispose()
        }

        // Генерируем мипмапы автоматически (очень важно для качества)
        Gdx.gl.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY)

        // Настройки фильтрации и повтора (идеально для твоего шейдера)
        Gdx.gl31.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_MIN_FILTER, GL30.GL_LINEAR_MIPMAP_LINEAR)
        Gdx.gl31.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_MAG_FILTER, GL30.GL_LINEAR)
        Gdx.gl31.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_WRAP_S, GL30.GL_REPEAT)
        Gdx.gl31.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_WRAP_T, GL30.GL_REPEAT)

        Gdx.gl31.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0)

        println("✅ TextureArray создан: $numLayers слоёв, ${width}×${height} px")
    }

    private fun createFBO() {
        val width = Gdx.graphics.width / 2
        val height = Gdx.graphics.height / 2
        fbo = FrameBuffer(Pixmap.Format.RGBA8888, width, height, true) // true = имеет depth-буфер (для твоего depth-test)
        println("✅ FBO создан: ${width}×${height} (для пост-процессинга)")
    }

    private fun createBlurFbo() {
        val w = (Gdx.graphics.width /*/ 2*/).coerceAtLeast(1)
        val h = (Gdx.graphics.height /*/ 2*/).coerceAtLeast(1)
        blurFbo = FrameBuffer(Pixmap.Format.RGBA8888, w, h, false) // depth не нужен

        // Линейная фильтрация — обязательна для красивого upsample
        blurFbo.getColorBufferTexture().setFilter(
            Texture.TextureFilter.Linear, Texture.TextureFilter.Linear
        )
        fbo.getColorBufferTexture().setFilter(
            Texture.TextureFilter.Linear, Texture.TextureFilter.Linear
        )
    }

    private fun createBlurShader() {
        val vertexShader = Gdx.files.internal("shaders/blur/blur.vert").readString()
        val fragmentShader = Gdx.files.internal("shaders/blur/blur.frag").readString()

        blurShader = ShaderProgram(vertexShader, fragmentShader)
        if (!blurShader.isCompiled) {
            throw RuntimeException("Blur shader compilation failed: ${blurShader.log}")
        }
    }

    private fun createSobelShader() {
        val vertexShader = Gdx.files.internal("shaders/post_process/post_process.vert").readString()
        val fragmentShader = Gdx.files.internal("shaders/post_process/post_process.frag").readString()

        sobelShader = ShaderProgram(vertexShader, fragmentShader)
        if (!sobelShader.isCompiled) {
            throw RuntimeException("Sobel shader compilation failed: ${sobelShader.log}")
        }
    }

    override fun create() {
        // Загружаем шейдеры (файлы будут обновлены ниже)
        val vertexShader = Gdx.files.internal("shaders/debug/circle.vert").readString()
        val fragmentShader = Gdx.files.internal("shaders/debug/circle.frag").readString()
        shader = ShaderProgram(vertexShader, fragmentShader)
        if (!shader.isCompiled) {
            throw RuntimeException("Shader compilation failed: ${shader.log}")
        }

        val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val attributes = VertexAttributes(
            VertexAttribute(
                VertexAttributes.Usage.Position,
                2,
                ShaderProgram.POSITION_ATTRIBUTE
            )
        )
        mesh = Mesh(false, 4, 0, attributes).apply { setVertices(vertices) }

        createTextureArray()
        createFBO()           // ← НОВОЕ
        createBlurFbo()
        createBlurShader()    // ← НОВОЕ
        createSobelShader()

//        linesTexture = Texture(Gdx.files.internal("shaders/parallax/p2.jpg"))
//        linesTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
//        linesTexture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat)

        create2SSBO()
    }

    private fun create2SSBO() {
        val ssboBuffer = BufferUtils.newIntBuffer(2)
        Gdx.gl31.glGenBuffers(2, ssboBuffer)
        ssbos[0] = ssboBuffer.get(0)
        ssbos[1] = ssboBuffer.get(1)

        for (i in 0..1) {
            ssboCapacities[i] = INITIAL_PARTICLE_CAPACITY * PARTICLE_STRUCT_SIZE
            Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, ssbos[i])
            Gdx.gl31.glBufferData(GL31.GL_SHADER_STORAGE_BUFFER, INITIAL_PARTICLE_CAPACITY * PARTICLE_STRUCT_SIZE, null, GL20.GL_DYNAMIC_DRAW)
            Gdx.gl31.glBindBufferBase(GL31.GL_SHADER_STORAGE_BUFFER, i, ssbos[i])
        }

        Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, 0)
    }

    private fun resize(dataSize: Int, targetIndex: Int, ssboId: Int) {
        if (dataSize > ssboCapacities[targetIndex]) {
            var newCapacity = ssboCapacities[targetIndex].toDouble()
            do {
                newCapacity *= 1.5
            } while (newCapacity < dataSize)

            val finalCapacity = newCapacity.toInt().coerceAtLeast(dataSize)

            Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, ssboId)
            Gdx.gl31.glBufferData(GL31.GL_SHADER_STORAGE_BUFFER, finalCapacity, null, GL20.GL_DYNAMIC_DRAW)
            Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, 0)

            ssboCapacities[targetIndex] = finalCapacity
        }
    }

    // Новый resize для экрана (вызывай при изменении размера окна/камеры)
    // Если у тебя уже есть метод resize(width, height) — просто добавь в него строку с FBO
    override fun resize(width: Int, height: Int) {
        val safeW = width.coerceAtLeast(1)
        val safeH = height.coerceAtLeast(1)

        if (::fbo.isInitialized) fbo.dispose()
        fbo = FrameBuffer(
            Pixmap.Format.RGBA8888,
            (safeW / 2).coerceAtLeast(1),
            (safeH / 2).coerceAtLeast(1),
            true
        )

        if (::blurFbo.isInitialized) blurFbo.dispose()
        val bw = (width /*/ 2*/).coerceAtLeast(1)
        val bh = (height /*/ 2*/).coerceAtLeast(1)
        blurFbo = FrameBuffer(Pixmap.Format.RGBA8888, bw, bh, false)

        // Linear фильтрация — обязательно для мягкого upsample и Sobel
        fbo.colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        blurFbo.colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)

        println("✅ FBOs resized → scene: ${width}×${height}, blur: ${bw}×${bh}")
    }

    var time = 0f

    override fun render(
        currentRead: ByteBuffer,
        cameraProjection: Matrix4,
        isNewFrame: Boolean,
        isClear: Boolean,
        worldX: Float,
        worldY: Float,
        blurAmount: Float,
        zoom: Float
    ) {

        val dataSize = currentRead.remaining()
        val numInstances = dataSize / PARTICLE_STRUCT_SIZE

        if (isNewFrame) {
            val writeIndex = 1 - currentReadIndex

            if (dataSize > 0) {
                resize(dataSize, writeIndex, ssbos[writeIndex])

                Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, ssbos[writeIndex])
                Gdx.gl31.glBufferSubData(GL31.GL_SHADER_STORAGE_BUFFER, 0, dataSize, currentRead)
                Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, 0)
            }

            currentReadIndex = writeIndex
        }

        if (usePostProcess) {
            fbo.begin()
        }

        Gdx.gl.glDisable(GL20.GL_BLEND)
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST)
        Gdx.gl.glDepthFunc(GL20.GL_LESS)
        Gdx.gl.glDepthMask(true)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

        shader.bind()
        shader.setUniformMatrix("u_projTrans", cameraProjection)
        shader.setUniformi("u_currentBuffer", currentReadIndex)
        shader.setUniformf("u_textureScale", 1.0f)
        shader.setUniformf("u_colorScale", if (usePostProcess) 0f else 1f)
        shader.setUniformi("u_textureArray", 0)

        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)
        Gdx.gl31.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, textureArray)

        mesh.bind(shader)
        Gdx.gl31.glDrawArraysInstanced(GL20.GL_TRIANGLE_STRIP, 0, 4, numInstances)
        mesh.unbind(shader)


        if (usePostProcess) {
            fbo.end()
        }

        if (usePostProcess) {
//            if (blurAmount > 0.001f) {
                blurFbo.begin()
//            }
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
            Gdx.gl.glDisable(GL20.GL_BLEND)

            invProjMatrix.set(cameraProjection).inv()

            sobelShader.bind()
            sobelShader.setUniformi("u_texture", 0)
            sobelShader.setUniformf("u_resolution", fbo.width.toFloat(), fbo.height.toFloat())
//            val zoomX10 = zoom * 10f
//            val sobel = if (zoomX10 < 0.12) 0.12f else if (zoomX10 > 0.24) 0.24f else zoomX10
            sobelShader.setUniformf("u_zoom", 0.12f)
//            println(zoomX10)

//            sobelShader.setUniformf("u_cameraPos", worldX, worldY)
//            sobelShader.setUniformMatrix("u_invProj", invProjMatrix)
//            sobelShader.setUniformf("u_parallaxStrength", 0.018f)

            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)
            fbo.colorBufferTexture.bind()

//            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE1)   // ← для lines
//            linesTexture.bind()

            mesh.bind(sobelShader)
            Gdx.gl.glDrawArrays(GL20.GL_TRIANGLE_STRIP, 0, 4)
            mesh.unbind(sobelShader)
//            if (blurAmount > 0.001f) {
                blurFbo.end()
//            }

//            if (blurAmount > 0.001f) {
                blurShader.bind()
                blurShader.setUniformi("u_texture", 0)
                blurShader.setUniformf("u_blurAmount", blurAmount * 0.5f)
                blurShader.setUniformf("u_resolution", blurFbo.width.toFloat(), blurFbo.height.toFloat())

                Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)
                blurFbo.colorBufferTexture.bind()

                mesh.bind(blurShader)
                Gdx.gl.glDrawArrays(GL20.GL_TRIANGLE_STRIP, 0, 4)
                mesh.unbind(blurShader)
//            }
        }

        Gdx.gl.glUseProgram(0)
    }

    override fun dispose() {
        shader.dispose()
        sobelShader.dispose()
        mesh.dispose()

        if (::fbo.isInitialized) fbo.dispose()
        blurShader.dispose()
        if (::blurFbo.isInitialized) blurFbo.dispose()
//        if (::linesTexture.isInitialized) linesTexture.dispose()

        if (textureArray != 0) {
            val deleteBuf = BufferUtils.newIntBuffer(1).apply {
                put(textureArray)
                flip()
            }
            Gdx.gl31.glDeleteTextures(1, deleteBuf)
            textureArray = 0
        }

        val deleteBuffer = BufferUtils.newIntBuffer(2).apply {
            put(ssbos[0])
            put(ssbos[1])
            flip()
        }
        Gdx.gl31.glDeleteBuffers(2, deleteBuffer)
    }
}
