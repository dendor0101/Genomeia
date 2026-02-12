package io.github.some_example_name.old.experiments

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.GL31
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.ScreenUtils
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.floor
import kotlin.system.measureNanoTime

class CircleInstancingSSBO : ApplicationAdapter() {

    private lateinit var camera: OrthographicCamera
    private lateinit var shader: ShaderProgram
    private lateinit var mesh: com.badlogic.gdx.graphics.Mesh
    private var circlesSSBO = 0
    private var neighborsSSBO = 0
    private val numCircles = 1_000
    private val maxNeighbors = 32  // Maximum neighbors per circle
    private val cellSize = 100f    // Grid cell size, tune based on max circle size (e.g., 2 * max_radius)

    // SSBO struct for circles: pos.x, pos.y (0-8), size (8-12), pad (12-16), color.r,g,b,a (16-32) -> 8 floats (32 bytes)
    private val floatsPerCircle = 8

    // For neighbors SSBO: int counts[numCircles] + int indices[numCircles * maxNeighbors]
    private val intsPerCircle = 1 + maxNeighbors  // But flattened

    // Temporary storage for circle data (to compute collisions)
    private val positions = Array(numCircles) { Vector2() }
    private val sizes = FloatArray(numCircles)

    override fun create() {
        // Setup camera
        camera = OrthographicCamera().apply {
            setToOrtho(false, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        }

        // Create quad mesh for circle base (normalized -1 to 1)
        val vertices = floatArrayOf(
            -1f, -1f,  // bottom-left
            1f, -1f,   // bottom-right
            -1f, 1f,   // top-left
            1f, 1f     // top-right
        )
        val attributes = VertexAttributes(
            VertexAttribute(
                VertexAttributes.Usage.Position,
                2,
                ShaderProgram.POSITION_ATTRIBUTE
            )
        )
        mesh = com.badlogic.gdx.graphics.Mesh(false, 4, 0, attributes).apply {
            setVertices(vertices)
        }

        // Load shaders
        val vertexShader = """
            #version 320 es
            precision highp float;
            layout(location = 0) in vec2 a_position;

            struct Circle {
                vec2 pos;
                float size;
                float pad; // Padding for alignment
                vec4 color;
            };

            layout(std430, binding = 0) buffer Circles {
                Circle circles[];
            };

            out vec2 v_texCoord;
            out vec4 v_color;
            flat out int v_instanceID;
            uniform mat4 u_projTrans;
            void main() {
                int instID = gl_InstanceID;
                Circle c = circles[instID];
                vec2 offsetPos = a_position * c.size * 0.5 + c.pos;
                v_texCoord = a_position * 0.5 + 0.5;
                v_color = c.color;
                v_instanceID = instID;
                gl_Position = u_projTrans * vec4(offsetPos, 0.0, 1.0);
            }
        """.trimIndent()

        val fragmentShader = """
            #version 320 es
            precision highp float;
            in vec2 v_texCoord;
            in vec4 v_color;
            flat in int v_instanceID;
            out vec4 fragColor;

            struct Circle {
                vec2 pos;
                float size;
                float pad; // Padding for alignment
                vec4 color;
            };

            layout(std430, binding = 0) buffer Circles {
                Circle circles[];
            };

            const int num_circles = $numCircles;
            const int max_neighbors = 32;

            layout(std430, binding = 1) buffer Neighbors {
                int counts[num_circles];    // counts[instanceID] = num neighbors
                int indices[];   // indices[instanceID * max_neighbors + j] = neighbor ID
            };

            void main() {
                int myID = v_instanceID;
                Circle c = circles[myID];
                vec2 my_pos = c.pos;
                float radius = c.size * 0.5;

                float dist_norm = length(v_texCoord - vec2(0.5));
                if (dist_norm > 0.5) discard;

                // World position of fragment
                vec2 frag_world = my_pos + (v_texCoord - vec2(0.5)) * c.size;
                float dist_my = length(frag_world - my_pos);

                // Check neighbors for Voronoi-like clipping
                bool draw = true;
                int num_neigh = counts[myID];
                float min_diff = 1e10;  // For optional boundary detection
                for (int j = 0; j < num_neigh; j++) {
                    int nid = indices[myID * max_neighbors + j];
                    Circle nc = circles[nid];
                    float dist_n = length(frag_world - nc.pos);
                    if (dist_n < dist_my) {
                        draw = false;
                        break;
                    }
                    min_diff = min(min_diff, abs(dist_my - dist_n));
                }

                if (!draw) discard;

                 //Optional: Draw black boundary if close to equidistant (Voronoi edge)
                 float threshold = 1.0;  // In world units, tune
                 if (min_diff < threshold) {
                     fragColor = v_color * 0.8;
                 } else {
                     fragColor = v_color;
                 }

//                fragColor = v_color;
            }
        """.trimIndent()

        shader = ShaderProgram(vertexShader, fragmentShader).apply {
            if (!isCompiled) {
                Gdx.app.log("Shader", "Compilation error: ${log}")
                Gdx.app.exit()
            }
        }

        // Setup Circles SSBO (binding 0)
        val ssboBuffer: IntBuffer = BufferUtils.newIntBuffer(1)
        Gdx.gl31.glGenBuffers(1, ssboBuffer)
        circlesSSBO = ssboBuffer.get(0)
        Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, circlesSSBO)
        val circlesBufferSize = numCircles * floatsPerCircle * 4L  // Bytes
        Gdx.gl31.glBufferData(GL31.GL_SHADER_STORAGE_BUFFER, circlesBufferSize.toInt(), null, GL20.GL_DYNAMIC_DRAW)
        Gdx.gl31.glBindBufferBase(GL31.GL_SHADER_STORAGE_BUFFER, 0, circlesSSBO)
        Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, 0)

        // Setup Neighbors SSBO (binding 1)
        Gdx.gl31.glGenBuffers(1, ssboBuffer)
        neighborsSSBO = ssboBuffer.get(0)
        Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, neighborsSSBO)
        val neighborsBufferSize = numCircles * (1 + maxNeighbors) * 4L  // counts + indices, in bytes
        Gdx.gl31.glBufferData(GL31.GL_SHADER_STORAGE_BUFFER, neighborsBufferSize.toInt(), null, GL20.GL_DYNAMIC_DRAW)
        Gdx.gl31.glBindBufferBase(GL31.GL_SHADER_STORAGE_BUFFER, 1, neighborsSSBO)
        Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, 0)
    }

    override fun render() {
        ScreenUtils.clear(0f, 0f, 0f, 1f)
        camera.update()
        shader.bind()
        shader.setUniformMatrix("u_projTrans", camera.combined)

        // Generate random circle data
        for (i in 0 until numCircles) {
            positions[i].set(MathUtils.random(0f, Gdx.graphics.width.toFloat()), MathUtils.random(0f, Gdx.graphics.height.toFloat()))
            sizes[i] = 40f//MathUtils.random(5f, 50f)
        }

        // Compute neighbors using sparse grid
        val neighbors = computeNeighbors()

        // Pack circles data into FloatBuffer
        val circlesBuffer: FloatBuffer = BufferUtils.newFloatBuffer(numCircles * floatsPerCircle)
        for (i in 0 until numCircles) {
            val x = positions[i].x
            val y = positions[i].y
            val size = sizes[i]
            val r = MathUtils.random(0f, 1f)
            val g = MathUtils.random(0f, 1f)
            val b = MathUtils.random(0f, 1f)
            val a = 1f
            circlesBuffer.put(x)
            circlesBuffer.put(y)
            circlesBuffer.put(size)
            circlesBuffer.put(0f)  // pad
            circlesBuffer.put(r)
            circlesBuffer.put(g)
            circlesBuffer.put(b)
            circlesBuffer.put(a)
        }
        circlesBuffer.flip()

        // Pack neighbors data into IntBuffer: first counts[0..numCircles-1], then indices[0..numCircles*maxNeighbors-1]
        val neighborsBuffer: IntBuffer = BufferUtils.newIntBuffer(numCircles * (1 + maxNeighbors))
        for (i in 0 until numCircles) {
            neighborsBuffer.put(neighbors[i].size)  // count
        }
        for (i in 0 until numCircles) {
            val neighList = neighbors[i]
            for (j in 0 until maxNeighbors) {
                neighborsBuffer.put(if (j < neighList.size) neighList[j] else -1)  // Pad with -1 if fewer
            }
        }
        neighborsBuffer.flip()

        val timeMs = measureNanoTime {
            // Update Circles SSBO
            Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, circlesSSBO)
            val circlesDataSize = circlesBuffer.remaining() * 4  // bytes
            Gdx.gl31.glBufferSubData(GL31.GL_SHADER_STORAGE_BUFFER, 0, circlesDataSize, circlesBuffer)
            Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, 0)

            // Update Neighbors SSBO
            Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, neighborsSSBO)
            val neighborsDataSize = neighborsBuffer.remaining() * 4  // bytes
            Gdx.gl31.glBufferSubData(GL31.GL_SHADER_STORAGE_BUFFER, 0, neighborsDataSize, neighborsBuffer)
            Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, 0)

            // Render
            Gdx.gl.glEnable(GL20.GL_BLEND)
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
            mesh.bind(shader)
            Gdx.gl31.glDrawArraysInstanced(GL20.GL_TRIANGLE_STRIP, 0, 4, numCircles)
            mesh.unbind(shader)
            Gdx.gl.glDisable(GL20.GL_BLEND)
        }

        println(Gdx.graphics.framesPerSecond.toString() + " " + (timeMs / 1_000_000.0))
        Thread.sleep(16)
    }

    private fun computeNeighbors(): Array<MutableList<Int>> {
        // Sparse grid: Map<Pair<gridX, gridY>, List<circle IDs>>
        val grid = mutableMapOf<Pair<Int, Int>, MutableList<Int>>()

        // Insert each circle into grid cells it overlaps
        for (i in 0 until numCircles) {
            val radius = sizes[i] / 2f
            val minX = floor((positions[i].x - radius) / cellSize).toInt()
            val maxX = floor((positions[i].x + radius) / cellSize).toInt()
            val minY = floor((positions[i].y - radius) / cellSize).toInt()
            val maxY = floor((positions[i].y + radius) / cellSize).toInt()

            for (gx in minX..maxX) {
                for (gy in minY..maxY) {
                    val key = Pair(gx, gy)
                    grid.getOrPut(key) { mutableListOf() }.add(i)
                }
            }
        }

        // For each circle, find neighbors from its cells
        val neighbors = Array(numCircles) { mutableListOf<Int>() }
        val checked = Array(numCircles) { mutableSetOf<Int>() }  // Avoid duplicates

        for (i in 0 until numCircles) {
            val radiusI = sizes[i] / 2f
            val minX = floor((positions[i].x - radiusI) / cellSize).toInt()
            val maxX = floor((positions[i].x + radiusI) / cellSize).toInt()
            val minY = floor((positions[i].y - radiusI) / cellSize).toInt()
            val maxY = floor((positions[i].y + radiusI) / cellSize).toInt()

            for (gx in minX..maxX) {
                for (gy in minY..maxY) {
                    val key = Pair(gx, gy)
                    grid[key]?.forEach { j ->
                        if (j > i && !checked[i].contains(j)) {  // Avoid self and duplicates (j > i)
                            val dist = positions[i].dst(positions[j])
                            val sumRadii = radiusI + sizes[j] / 2f
                            if (dist <= sumRadii) {
                                neighbors[i].add(j)
                                neighbors[j].add(i)  // Mutual
                            }
                            checked[i].add(j)
                            checked[j].add(i)
                        }
                    }
                }
            }

            // Limit to maxNeighbors if exceeded (rare, but truncate)
            if (neighbors[i].size > maxNeighbors) {
                neighbors[i] = neighbors[i].subList(0, maxNeighbors)
            }
        }

        return neighbors
    }

    override fun dispose() {
        mesh.dispose()
        shader.dispose()
        val ssboBuffer: IntBuffer = BufferUtils.newIntBuffer(2)
        ssboBuffer.put(circlesSSBO)
        ssboBuffer.put(neighborsSSBO)
        ssboBuffer.flip()
        Gdx.gl31.glDeleteBuffers(2, ssboBuffer)
    }
}
