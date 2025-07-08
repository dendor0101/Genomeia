package io.github.some_example_name.old.good_one.shader

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.*
import com.badlogic.gdx.graphics.GL32.GL_TEXTURE_BUFFER
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.GdxRuntimeException
import io.github.some_example_name.attempts.game.physics.WorldGenerator
import io.github.some_example_name.old.good_one.editor.GenomeEditorRefactored
import io.github.some_example_name.old.good_one.ui.Pause
import io.github.some_example_name.old.good_one.ui.Play
import io.github.some_example_name.old.good_one.ui.UiProcessor
import io.github.some_example_name.old.logic.CellManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer


class ShaderManager(
    val uiProcessor: UiProcessor,
    val genomeEditor: GenomeEditorRefactored,
    val cellManager: CellManager
) {

    private var uboId: Int = 0
    private lateinit var uboBuffer: ByteBuffer
    private var shader: ShaderProgram
    private var mesh: Mesh

    private var grid: Array<Array<Triple<MutableList<Int>, MutableList<Int>, MutableList<Int>>>>
    private var screenWidth = 0
    private var screenHeight = 0
    private var CELLS_AMOUNT = 0
    private var CELL_SIZE = 0f
    private var CELLS_HEIGHT_AMOUNT = 0
    private var CELLS_WIDTH_AMOUNT = 24 + 2


    init {
        screenWidth = Gdx.graphics.width
        screenHeight = Gdx.graphics.height
        CELL_SIZE = screenWidth / (CELLS_WIDTH_AMOUNT - 2f)
        CELLS_HEIGHT_AMOUNT = 26//(screenHeight / CELL_SIZE + 2).toInt()
        CELLS_AMOUNT = CELLS_HEIGHT_AMOUNT * CELLS_WIDTH_AMOUNT
//        cellManager.zoomManager.shaderCellSize = CELL_SIZE / 40f

        println("$screenWidth $screenHeight $CELL_SIZE $CELLS_WIDTH_AMOUNT $CELLS_HEIGHT_AMOUNT $CELLS_AMOUNT")

        grid = Array(CELLS_HEIGHT_AMOUNT) {
            Array(CELLS_WIDTH_AMOUNT) {
                Triple(
                    mutableListOf(),
                    mutableListOf(),
                    mutableListOf()
                )
            }
        }

        createUBO()
        // Создаем шейдерную программу
        shader = ShaderProgram(vertexShader, fragmentShader)
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

    private val textureWidth = 2048
    private val textureHeight = 1
    private val textureId: Int

    private val totalTexels = textureWidth * textureHeight
    private val floatArray = FloatArray(totalTexels * 4)
    private val floatBuffer: FloatBuffer

    val gridSize = 676
    val cellsSize = 1000
    val cellsOffset = gridSize // смещение vec4 u_cells в текстуре

    init {
        floatBuffer = ByteBuffer
            .allocateDirect(floatArray.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        // Создание текстуры
        textureId = Gdx.gl.glGenTexture()
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, textureId)
        Gdx.gl.glTexImage2D(
            GL20.GL_TEXTURE_2D,
            0,
            GL30.GL_RGBA32F,
            textureWidth,
            textureHeight,
            0,
            GL20.GL_RGBA,
            GL20.GL_FLOAT,
            null
        )
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MIN_FILTER, GL20.GL_NEAREST)
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MAG_FILTER, GL20.GL_NEAREST)
    }



    //Вызывается только при инициализации
    private fun createUBO() {
        val maxUboSize = BufferUtils.newIntBuffer(1)
        Gdx.gl30.glGetIntegerv(GL30.GL_MAX_UNIFORM_BLOCK_SIZE, maxUboSize)
        val maxSupportedUboSize = maxUboSize[0]

        uboBuffer = ByteBuffer.allocateDirect(maxSupportedUboSize)
            .order(ByteOrder.nativeOrder())



        uboId = Gdx.gl.glGenBuffer()
        Gdx.gl.glBindBuffer(GL30.GL_UNIFORM_BUFFER, uboId)
        Gdx.gl.glBufferData(GL30.GL_UNIFORM_BUFFER, maxSupportedUboSize, null, GL30.GL_DYNAMIC_DRAW)
        Gdx.gl30.glBindBufferBase(GL30.GL_UNIFORM_BUFFER, 0, uboId)
    }

    fun render(cells: List<CellShaderModel>, substances: List<SubShaderModel>, links: List<LinkShaderModel>) {
        for (i in 0..<CELLS_HEIGHT_AMOUNT) {
            for (j in 0..<CELLS_WIDTH_AMOUNT) {
                grid[i][j].apply {
                    first.clear()
                    second.clear()
                    third.clear()
                }
            }
        }

        val editorCells = genomeEditor.cellsCopy.values.toList().map {
            CellShaderModel(
                index = 0,
                x = it.x,
                y = it.y,
                ax = 0f,
                ay = 0f,
                r = it.colorCore.r,
                g = it.colorCore.g,
                b = it.colorCore.b,
                energy = 3f,
                cellMode = when {
                    it.isSelected -> 3f
                    it.isAdded -> 1f
                    else -> 0f
                },
                cellType = it.cellTypeId.toFloat()
            )
        }

        (if (uiProcessor.uiState is Play) cells else editorCells)/*mock*/.forEachIndexed { id, it ->
            val startX = (it.x / CELL_SIZE).toInt()
            val startY = (it.y / CELL_SIZE).toInt()
            grid[startY][startX].first.add(id)
        }
        substances.forEachIndexed { id, it ->
            val startX = (it.x / CELL_SIZE).toInt()
            val startY = (it.y / CELL_SIZE).toInt()
            grid[startY][startX].second.add(id)
        }
        links.forEachIndexed { id, it ->
            val startX = (it.x / CELL_SIZE).toInt()
            val startY = (it.y / CELL_SIZE).toInt()
            grid[startY][startX].third.add(id)
        }

        shader.bind()
        when (uiProcessor.uiState) {
            is Pause -> {
                updateUBO(editorCells, substances, links)
            }

            Play -> {
                updateUBO(cells, substances, links)
            }
        }

        shader.setUniformf("u_screenSize", Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        shader.setUniformf("u_zoom", cellManager.zoomManager.zoomScale)
        shader.setUniformi("u_gridWidth", CELLS_WIDTH_AMOUNT)
        shader.setUniformi("u_gridHeight", CELLS_HEIGHT_AMOUNT)
        shader.setUniformi("u_playMode", if (uiProcessor.uiState is Pause) 1 else 2)
        mesh.render(shader, GL20.GL_TRIANGLES)
    }

    //Вызывается каждый кадр
    private fun updateUBO(
        cells: List<CellShaderModel>,
        substances: List<SubShaderModel>,
        links: List<LinkShaderModel>
    ) {
        uboBuffer.clear()

        val intView = uboBuffer.asIntBuffer()
        var indexCounter = 0

        for (i in 0..<CELLS_HEIGHT_AMOUNT) {
            for (j in 0..<CELLS_WIDTH_AMOUNT) {
                grid[i][j].apply {
                    val cell = grid[i][j]
                    val batchSize =
                        cell.first.size * CELLS_FLOAT_COUNT + cell.second.size * SUBS_FLOAT_COUNT + cell.third.size * LINKS_FLOAT_COUNT
                    intView.put(if (cell.first.isEmpty() && cell.second.isEmpty() && cell.third.isEmpty()) -1 else indexCounter)
                    intView.put(batchSize)
                    intView.put(0)//TODO Если заполнить, то можно будет передать x2 данных
                    intView.put(0)
                    indexCounter += batchSize
                }
            }
        }

        uboBuffer.position(CELLS_AMOUNT * 16) // Переходим к началу vec2-данных
        val floatView = uboBuffer.asFloatBuffer()

        for (i in 0..<CELLS_HEIGHT_AMOUNT) {
            for (j in 0..<CELLS_WIDTH_AMOUNT) {
                grid[i][j].first.forEach {
                    val cell = cells[it]
                    floatView.put(1f)//type (cell, substance or link)
                    floatView.put(cell.x)
                    floatView.put(cell.y)
                    floatView.put(cell.ax)
                    floatView.put(cell.ay)
                    floatView.put(cell.r)
                    floatView.put(cell.g)
                    floatView.put(cell.b)
                    floatView.put(cell.energy)
                    floatView.put(cell.cellMode)//cellMode
                    floatView.put(0f)//cellType
                    floatView.put(0f)//angle
                }

                grid[i][j].third.forEach {
                    val link = links[it]

                    floatView.put(2f)//type (cell, substance or link)
                    floatView.put(link.xc1)
                    floatView.put(link.yc1)
                    floatView.put(link.xc2)
                    floatView.put(link.yc2)
                    floatView.put(link.rc1)
                    floatView.put(link.gc1)
                    floatView.put(link.bc1)
                    floatView.put(link.rc2)
                    floatView.put(link.gc2)
                    floatView.put(link.bc2)
                }

                grid[i][j].second.forEach {
                    val sub = substances[it]
                    floatView.put(3f)//type (cell, substance or link)
                    floatView.put(sub.x)
                    floatView.put(sub.y)
                    floatView.put(sub.radius)//radius
                    floatView.put(sub.r)
                    floatView.put(sub.g)
                    floatView.put(sub.b)
                }
            }
        }

        for (i in 1..(indexCounter % 4)) {
            floatView.put(0f)
            indexCounter++
        }

        // 3. Отправляем данные в GPU
        uboBuffer.position(0) // Возвращаемся в начало буфера
        uboBuffer.limit(CELLS_AMOUNT * 16 + indexCounter * 4) // Устанавливаем лимит

        Gdx.gl.glBindBuffer(GL31.GL_UNIFORM_BUFFER, uboId)
        Gdx.gl.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0, uboBuffer.limit(), uboBuffer)
        Gdx.gl.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0)
    }

    companion object {
        private const val MAX_FLOATS_AMOUNT = 15700
        const val CELLS_FLOAT_COUNT = 10
        const val SUBS_FLOAT_COUNT = 7
        const val LINKS_FLOAT_COUNT = 11
    }
}
