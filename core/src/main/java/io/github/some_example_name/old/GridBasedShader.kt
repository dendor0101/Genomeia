package io.github.some_example_name.old


import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.*
import com.badlogic.gdx.graphics.VertexAttributes.Usage
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Vector2
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
//TODO
/* Уплотнить grid
* Сделать фиксированную ширину сетки, количество ячеек - 48, выоста будет высчитываться сама
* vec2 fragCoord = v_texCoords * u_resolution; и кажется через это можно будет сделать динамическое изменение разрешение экрана*/
class GridBasedShader : ApplicationAdapter() {
    private lateinit var shader: ShaderProgram
    private lateinit var mesh: Mesh
    private var uboId: Int = 0
    private lateinit var uboBuffer: ByteBuffer

    private val circleCount = 2500
    private val circleRadius = 20f
    private val CELL_SIZE = circleRadius * 2
    private lateinit var grid: Array<Array<MutableList<Int>>>

    private val circles = mutableListOf<Vector2>()
    private val velocities = mutableListOf<Vector2>()

    private var screenWidth = 0
    private var screenHeight = 0
    private var gridWidth = 0
    private var gridHeight = 0
    private var CELLS_AMOUNT = 0

    override fun create() {
        screenWidth = Gdx.graphics.width
        screenHeight = Gdx.graphics.height
        gridWidth = (screenWidth / CELL_SIZE).toInt()
        gridHeight = (screenHeight / CELL_SIZE).toInt()
        CELLS_AMOUNT = gridWidth * gridHeight
        grid = Array(gridWidth) { Array(gridHeight) { mutableListOf() } }
        repeat(circleCount) {
            circles.add(
                Vector2(
                    Random.nextFloat() * (screenWidth / 2) + 240,
                    Random.nextFloat() * (screenHeight / 2) + 240
                )
            )
            velocities.add(Vector2(Random.nextFloat() - 0.5f, Random.nextFloat() - 0.5f).nor().scl(90f))
        }

        circles.forEachIndexed { index, particle ->
            val cellPositions = grid[(particle.x / CELL_SIZE).toInt()][(particle.y / CELL_SIZE).toInt()]
            cellPositions.add(index)
        }

        createUBO()

        shader = ShaderProgram(VERT_SHADER, FRAG_SHADER)
        if (!shader.isCompiled) throw Exception("Shader compile error: ${shader.log}")

        mesh = Mesh(
            true, 4, 6,
            VertexAttribute(Usage.Position, 2, "a_position"),
            VertexAttribute(Usage.TextureCoordinates, 2, "a_texCoords")
        )
        mesh.setVertices(
            floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                1f, 1f, 1f, 1f,
                -1f, 1f, 0f, 1f
            )
        )
        mesh.setIndices(shortArrayOf(0, 1, 2, 2, 3, 0))
    }

    private fun createUBO() {
        val gridSize = CELLS_AMOUNT * 16
        val cellsSize = circleCount * 16  // circleCount × vec2 (8 байт)
        val uboSize = gridSize + cellsSize

        uboBuffer = ByteBuffer.allocateDirect(uboSize)
            .order(ByteOrder.nativeOrder())

        uboId = Gdx.gl.glGenBuffer()
        Gdx.gl.glBindBuffer(GL30.GL_UNIFORM_BUFFER, uboId)
        Gdx.gl.glBufferData(GL30.GL_UNIFORM_BUFFER, uboSize, null, GL30.GL_DYNAMIC_DRAW)
        Gdx.gl30.glBindBufferBase(GL30.GL_UNIFORM_BUFFER, 0, uboId)
    }

    private fun updateUBO() {
        uboBuffer.clear()

        val intView = uboBuffer.asIntBuffer()
        var indexCounter = 0
        for (i in 0..<gridHeight) {
            for (j in 0..<gridWidth) {
                val cell = grid[j][i]
                intView.put(if (cell.isNotEmpty()) indexCounter else -1) // index
                intView.put(cell.size)                        // count
                intView.put(0)//TODO Если заполнить, то можно будет передать x2 данных
                intView.put(0)
                indexCounter += cell.size
            }
        }

        // 2. Переключаемся на u_cells (circleCount × vec2)
        uboBuffer.position(CELLS_AMOUNT * 16) // Переходим к началу vec2-данных
        val floatView = uboBuffer.asFloatBuffer()
        for (i in 0..<gridHeight) {
            for (j in 0..<gridWidth) {
                grid[j][i].forEach {
                    val circle = circles[it]
                    floatView.put(circle.x)
                    floatView.put(circle.y)
                    floatView.put(0f)//TODO Если заполнить, то можно будет передать x2 данных
                    floatView.put(0f)
                }
            }
        }

        // 3. Отправляем данные в GPU
        uboBuffer.position(0) // Возвращаемся в начало буфера
        uboBuffer.limit(CELLS_AMOUNT * 16 + circleCount * 16) // Устанавливаем лимит

        Gdx.gl.glBindBuffer(GL31.GL_UNIFORM_BUFFER, uboId)
        Gdx.gl.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0, uboBuffer.limit(), uboBuffer)
        Gdx.gl.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0)
    }

    override fun render() {
        val deltaTime = Gdx.graphics.deltaTime

        for (i in 0 until circleCount) {
            val startX = (circles[i].x / CELL_SIZE).toInt()
            val startY = (circles[i].y / CELL_SIZE).toInt()
            val pos = circles[i]
            val vel = velocities[i]

            pos.add(vel.x * deltaTime, vel.y * deltaTime)

            // отскок от границ
            if (pos.x > screenWidth - circleRadius) {
                vel.x *= -1
                pos.x = screenWidth - circleRadius
            }
            if (pos.x < circleRadius) {
                vel.x *= -1
                pos.x = circleRadius
            }
            if (pos.y < circleRadius) {
                vel.y *= -1
                pos.y = circleRadius
            }
            if (pos.y > screenHeight - circleRadius) {
                vel.y *= -1
                pos.y = screenHeight - circleRadius
            }

            pos.x = pos.x.coerceIn(0f, screenWidth.toFloat())
            pos.y = pos.y.coerceIn(0f, screenHeight.toFloat())

            val movedX = (circles[i].x / CELL_SIZE).toInt()
            val movedY = (circles[i].y / CELL_SIZE).toInt()
            if (startX != movedX || startY != movedY) {
                grid[startX][startY].remove(i)
                grid[movedX][movedY].add(i)
            }
        }

        updateUBO()

        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        shader.begin()
        shader.setUniformf("u_resolution", screenWidth.toFloat(), screenHeight.toFloat())
        shader.setUniformf("u_cellSize", CELL_SIZE)
        shader.setUniformf("u_radius", circleRadius)
        shader.setUniformi("u_gridWidth", gridWidth)
        mesh.render(shader, GL20.GL_TRIANGLES)
        shader.end()
    }


    override fun dispose() {
        shader.dispose()
        mesh.dispose()
        Gdx.gl.glDeleteBuffer(uboId)
    }


    val VERT_SHADER
        get() = """
            #version 330 core
            in vec2 a_position;
            in vec2 a_texCoords;
            out vec2 v_texCoords;
            void main() {
                v_texCoords = a_texCoords;
                gl_Position = vec4(a_position, 0.0, 1.0);
            }
        """

    val FRAG_SHADER
        get() = """
            #version 330 core
            out vec4 fragColor;
            in vec2 v_texCoords;

            uniform vec2 u_resolution;
            uniform float u_radius;
            uniform float u_cellSize;
            uniform int u_gridWidth;

            layout(std140) uniform CircleGrid {
                ivec2 u_grid[$CELLS_AMOUNT]; // Each cell has (index, count)
                vec2 u_cells[${circles.size}]; // All circles positions
            };

            ivec2 getGridData(int index) {
                if (index < 0 || index >= $CELLS_AMOUNT) {
                    return ivec2(-1, 0);
                }
                return u_grid[index];
            }

            bool isInsideCell(ivec2 cellData, vec2 fragCoord, float radius) {
                for (int i = 0; i < cellData.y; i++) {
                    vec2 pos = u_cells[cellData.x + i];
                    if (distance(pos, fragCoord) < radius) {
                        return true;
                    }
                }
                return false;
            }

            void main() {
                vec2 fragCoord = v_texCoords * u_resolution;

                // Calculate grid cell coordinates
                int cellX = int(fragCoord.x / u_cellSize);
                int cellY = int(fragCoord.y / u_cellSize);

                // Check if cell is within grid bounds
                if (cellX < 0 || cellY < 0 || cellX >= u_gridWidth || cellY >= u_gridWidth) {
                    fragColor = vec4(0.0);
                    return;
                }

                ivec2 cellData   = getGridData(cellY * u_gridWidth + cellX);
                ivec2 cellDataL  = getGridData(cellY * u_gridWidth + (cellX-1));
                ivec2 cellDataR  = getGridData(cellY * u_gridWidth + (cellX+1));
                ivec2 cellDataT  = getGridData((cellY+1) * u_gridWidth + cellX);
                ivec2 cellDataB  = getGridData((cellY-1) * u_gridWidth + cellX);
                ivec2 cellDataLT = getGridData((cellY+1) * u_gridWidth + (cellX-1));
                ivec2 cellDataLB = getGridData((cellY-1) * u_gridWidth + (cellX-1));
                ivec2 cellDataRT = getGridData((cellY+1) * u_gridWidth + (cellX+1));
                ivec2 cellDataRB = getGridData((cellY-1) * u_gridWidth + (cellX+1));

                if (cellData.x != -1) {
                    if (isInsideCell(cellData  , fragCoord, u_radius)) { fragColor = vec4(1.0); return; }
                }
                if (cellDataL.x != -1) {
                    if (isInsideCell(cellDataL  , fragCoord, u_radius)) { fragColor = vec4(1.0); return; }
                }
                if (cellDataR.x != -1) {
                    if (isInsideCell(cellDataR  , fragCoord, u_radius)) { fragColor = vec4(1.0); return; }
                }
                if (cellDataT.x != -1) {
                    if (isInsideCell(cellDataT  , fragCoord, u_radius)) { fragColor = vec4(1.0); return; }
                }
                if (cellDataB.x != -1) {
                    if (isInsideCell(cellDataB  , fragCoord, u_radius)) { fragColor = vec4(1.0); return; }
                }
                if (cellDataLT.x != -1) {
                    if (isInsideCell(cellDataLT  , fragCoord, u_radius)) { fragColor = vec4(1.0); return; }
                }
                if (cellDataLB.x != -1) {
                    if (isInsideCell(cellDataLB  , fragCoord, u_radius)) { fragColor = vec4(1.0); return; }
                }
                if (cellDataRT.x != -1) {
                    if (isInsideCell(cellDataRT  , fragCoord, u_radius)) { fragColor = vec4(1.0); return; }
                }
                if (cellDataRB.x != -1) {
                    if (isInsideCell(cellDataRB  , fragCoord, u_radius)) { fragColor = vec4(1.0); return; }
                }

                fragColor = vec4(0.0);
            }
        """

}

