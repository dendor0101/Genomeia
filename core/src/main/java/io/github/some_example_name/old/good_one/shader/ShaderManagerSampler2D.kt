package io.github.some_example_name.old.good_one.shader

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.GL20.GL_TEXTURE_2D
import com.badlogic.gdx.graphics.GL30
import com.badlogic.gdx.graphics.GL30.GL_FLOAT
import com.badlogic.gdx.graphics.GL30.GL_INT
import com.badlogic.gdx.graphics.GL30.GL_RG32I
import com.badlogic.gdx.graphics.GL30.GL_RGBA
import com.badlogic.gdx.graphics.GL30.GL_RGBA16F
import com.badlogic.gdx.graphics.GL30.GL_RGBA32F
import com.badlogic.gdx.graphics.GL30.GL_RG_INTEGER
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.TextureData
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.GdxRuntimeException
import io.github.some_example_name.old.screens.GlobalSettings.MSAA
import io.github.some_example_name.old.good_one.SHADER_TEXTURE_SIZE
import io.github.some_example_name.old.good_one.isShowCell
import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.GridManager.Companion.WORLD_CELL_HEIGHT
import io.github.some_example_name.old.world_logic.GridManager.Companion.WORLD_CELL_WIDTH
import io.github.some_example_name.old.world_logic.isPlay
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.ceil

class ShaderManagerSampler2D(
    val cellManager: CellManager
) {

    private var shader: ShaderProgram
    private var mesh: Mesh

    var screenWidth = 0
    var screenHeight = 0
    private var CELL_SIZE = 0f
    private var GRID_SIZE = 0
    private val intTexture = IntTexture2D_RG32I(SHADER_TEXTURE_SIZE, SHADER_TEXTURE_SIZE)
    private val floatTexture = FloatTexture2D(SHADER_TEXTURE_SIZE, SHADER_TEXTURE_SIZE)
    private val pheromoneFloatTexture = FloatTexture2D(WORLD_CELL_WIDTH, WORLD_CELL_HEIGHT)

    private var prevZoom = cellManager.zoomManager.zoomScale

    init {
        updateGrid()
        val vertFile = Gdx.files.internal("vertexShader.vert").readString()
        val fragFile = Gdx.files.internal("fragmentShader.frag").readString()

        shader = ShaderProgram(vertFile, fragFile)

        if (!shader.isCompiled) {
            throw GdxRuntimeException("Shader compilation failed: ${shader.log}")
        }

        // Создаем mesh для отрисовки полноэкранного квадрата
        mesh = Mesh(true, 4, 6, VertexAttribute.Position())
        mesh.setVertices(
            floatArrayOf(
                -1f, -1f, 0f,  // Левый нижний угол
                1f, -1f, 0f,   // Правый нижний угол
                1f, 1f, 0f,    // Правый верхний угол
                -1f, 1f, 0f    // Левый верхний угол
            )
        )
        mesh.setIndices(shortArrayOf(0, 1, 2, 2, 3, 0))
    }

    fun updateGrid() {
        screenWidth = Gdx.graphics.width
        screenHeight = Gdx.graphics.height
        CELL_SIZE = (screenWidth / 24f) * cellManager.zoomManager.zoomScale
        GRID_SIZE = ceil((screenWidth / CELL_SIZE) + 1).toInt()
        cellManager.zoomManager.shaderCellSize = screenWidth / 960f
        prevZoom = cellManager.zoomManager.zoomScale
    }

    fun render() {
        val cameraX = (cellManager.zoomManager.screenOffsetX * cellManager.zoomManager.shaderCellSize * cellManager.zoomManager.zoomScale) % (CELL_SIZE)
        val cameraY = (cellManager.zoomManager.screenOffsetY * cellManager.zoomManager.shaderCellSize * cellManager.zoomManager.zoomScale) % CELL_SIZE
        if (cellManager.zoomManager.zoomScale != prevZoom || screenWidth != Gdx.graphics.width || screenHeight != Gdx.graphics.height) {
            updateGrid()
        }

        val aspectRatio = Gdx.graphics.height.toFloat() / Gdx.graphics.width.toFloat()

        if ((isShowCell && !isPlay) || isPlay) {
            floatTexture.update(cellManager.bufferFloat)
            intTexture.update(cellManager.bufferInt)
        } else {
            floatTexture.update(cellManager.bufferFloatEmpty)
            intTexture.update(cellManager.bufferIntEmpty)
        }
        pheromoneFloatTexture.update(cellManager.bufferPheromoneFloat)

        shader.bind()
        intTexture.texture.bind(0)
        shader.setUniformi("u_intTexture", 0)

        floatTexture.texture.bind(1)
        shader.setUniformi("u_floatTexture", 1)

        pheromoneFloatTexture.texture.bind(2)
        shader.setUniformi("u_pheromoneFloatTexture", 2)

        shader.setUniformf("u_screenSize", Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        shader.setUniformf("u_aspectRatio", aspectRatio)
        shader.setUniformf("u_cellSizepx", CELL_SIZE)
        shader.setUniformf("u_gridOffset", cameraX, cameraY)
        shader.setUniformf("u_backgroundColor", cellManager.backgroundColor)
        shader.setUniformi("u_msaa", MSAA)
        mesh.render(shader, GL20.GL_TRIANGLES)

        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE1)
        Gdx.gl.glBindTexture(GL_TEXTURE_2D, 0)

        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)
        Gdx.gl.glBindTexture(GL_TEXTURE_2D, 0)

        Gdx.gl.glUseProgram(0)

    }

    companion object {
        const val CELLS_FLOAT_COUNT = 10
        const val SUBS_FLOAT_COUNT = 7
        const val LINKS_FLOAT_COUNT = 11
    }
}

class FloatTexture2D(val widthSize: Int, val heightSize: Int) {
    val texture: Texture

    init {
        val texData = object : TextureData {
            override fun isPrepared() = true
            override fun prepare() {}
            override fun consumePixmap(): Pixmap {
                throw GdxRuntimeException("This TextureData implementation does not return a Pixmap")
            }

            override fun disposePixmap() = false
            override fun consumeCustomData(target: Int) {
                Gdx.gl.glTexImage2D(
                    target, 0, GL_RGBA32F, widthSize, heightSize, 0,
                    GL_RGBA, GL_FLOAT, null
                )
            }

            override fun getWidth() = widthSize
            override fun getHeight() = heightSize
            override fun getFormat() = Pixmap.Format.RGBA8888
            override fun getType() = TextureData.TextureDataType.Custom
            override fun useMipMaps() = false
            override fun isManaged() = false
        }

        texture = Texture(texData)
        texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
        texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge)
    }

    fun update(bufferFloat: FloatBuffer) {

        texture.bind()

        Gdx.gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, 1)
        //При некоторых обстаятельствах очень долго выполняется до 16 мс
        Gdx.gl.glTexSubImage2D(
            GL_TEXTURE_2D, 0, 0, 0,
            widthSize, heightSize,
            GL_RGBA, GL_FLOAT, bufferFloat
        )
    }

    fun dispose() {
        texture.dispose()
    }
}

class IntTexture2D_RG32I(val widthSize: Int, val heightSize: Int) {
    val texture: Texture

    init {
        val texData = object : TextureData {
            override fun isPrepared() = true
            override fun prepare() {}
            override fun consumePixmap(): Pixmap {
                throw GdxRuntimeException("This TextureData implementation does not return a Pixmap")
            }
            override fun disposePixmap(): Boolean = false

            override fun consumeCustomData(target: Int) {
                Gdx.gl.glTexImage2D(
                    target,
                    0,
                    GL_RG32I,
                    widthSize,
                    heightSize,
                    0,
                    GL_RG_INTEGER,
                    GL_INT,
                    null
                )
            }

            override fun getWidth() = widthSize
            override fun getHeight() = heightSize
            override fun getFormat() = Pixmap.Format.RGBA8888
            override fun getType() = TextureData.TextureDataType.Custom
            override fun useMipMaps() = false
            override fun isManaged() = false
        }

        texture = Texture(texData)
        texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
        texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge)
    }

    fun update(bufferInt: IntBuffer) {
        texture.bind()
        Gdx.gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, 1)
        Gdx.gl.glTexSubImage2D(
            GL_TEXTURE_2D,
            0,
            0,
            0,
            widthSize,
            heightSize,
            GL30.GL_RG_INTEGER,
            GL30.GL_INT,
            bufferInt
        )
    }

    fun dispose() {
        texture.dispose()
    }
}
