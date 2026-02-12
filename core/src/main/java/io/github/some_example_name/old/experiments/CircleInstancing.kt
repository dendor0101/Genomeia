package io.github.some_example_name.old.experiments

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.GL20.GL_DYNAMIC_DRAW
import com.badlogic.gdx.graphics.GL31
import com.badlogic.gdx.graphics.GL31.GL_SHADER_STORAGE_BUFFER
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.ScreenUtils
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.system.measureNanoTime

class CircleInstancing : ApplicationAdapter() {

    private lateinit var camera: OrthographicCamera
    private lateinit var shader: ShaderProgram
    private lateinit var mesh: com.badlogic.gdx.graphics.Mesh
    private var ssbo = 0
    private val numCircles = 5_000
    private lateinit var circleData: FloatArray
    private val floatsPerCircle = 12 // pos.x, pos.y, size, pad1, color.r,g,b,a, vx, vy, pad2, pad3
    private val minSize = 5f
    private val maxSize = 50f
    private val minPos = 50f // Avoid spawning near (0,0)

    override fun create() {
        // Setup camera to match window size
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
                float pad1;
                vec4 color;
                vec2 velocity;
                float pad2;
                float pad3;
            };

            layout(std430, binding = 0) buffer Circles {
                Circle circles[];
            };

            out vec2 v_texCoord;
            out vec4 v_color;
            uniform mat4 u_projTrans;
            void main() {
                int instID = gl_InstanceID;
                Circle c = circles[instID];
                float clampedSize = clamp(c.size, 5.0, 50.0);
                vec2 clampedPos = clamp(c.pos, vec2(50.0), vec2(${Gdx.graphics.width.toFloat() - 50f}, ${Gdx.graphics.height.toFloat() - 50f}));
                vec2 offsetPos = a_position * clampedSize * 0.5 + clampedPos;
                v_texCoord = a_position * 0.5 + 0.5;
                v_color = c.color;
                gl_Position = u_projTrans * vec4(offsetPos, 0.0, 1.0);
            }
        """.trimIndent()

        val fragmentShader = """
            #version 320 es
            precision highp float;

            in vec2 v_texCoord;
            in vec4 v_color;
            out vec4 fragColor;

            void main() {
                // расстояние от центра круга (0.5, 0.5)
                float dist = length(v_texCoord - vec2(0.5));

                // радиус круга = 0.5 (так как мы нормализуем от quad [-1..1])
                float radius = 0.5;

                // толщина границы = 10% от радиуса
                float border = radius * 0.1;

                // антиалиасинг (ширина сглаживания)
                float aa = fwidth(dist);

                // альфа маска для круга
                float circleMask = 1.0 - smoothstep(radius - aa, radius + aa, dist);

                // маска для границы (узкая область около радиуса)
                float borderMask = smoothstep(radius - border - aa, radius - border + aa, dist) *
                                   (1.0 - smoothstep(radius - aa, radius + aa, dist));

                // цвет заливки круга
                vec4 fillColor = v_color * circleMask;

                // цвет границы (менее прозрачный)
                vec4 borderColor = vec4(v_color.rgb, v_color.a * 0.5) * borderMask;

                // итоговый цвет
                fragColor = fillColor + borderColor;

                // если полностью прозрачный — отбросить фрагмент (оптимизация)
                if (fragColor.a < 0.01) discard;
            }
        """.trimIndent()

        shader = ShaderProgram(vertexShader, fragmentShader).apply {
            if (!isCompiled) {
                Gdx.app.log("Shader", "Compilation error: ${log}")
                Gdx.app.exit()
            }
        }
        // Setup SSBO
        val ssboBuffer: IntBuffer = BufferUtils.newIntBuffer(1)
        Gdx.gl31.glGenBuffers(1, ssboBuffer)
        ssbo = ssboBuffer.get(0)
        Gdx.gl31.glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo)
        val bufferSize = numCircles * floatsPerCircle * 4L // 4 bytes per float
        Gdx.gl31.glBufferData(GL_SHADER_STORAGE_BUFFER, bufferSize.toInt(), null, GL_DYNAMIC_DRAW)
        Gdx.gl31.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssbo)
        Gdx.gl31.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)
        Gdx.gl31.glEnable(GL31.GL_BLEND)
        Gdx.gl31.glBlendFunc(GL31.GL_SRC_ALPHA, GL31.GL_ONE_MINUS_SRC_ALPHA)

        // Initialize circle data
        circleData = FloatArray(numCircles * floatsPerCircle) { 0f } // Initialize with zeros
        for (i in 0 until numCircles) {
            val offset = i * floatsPerCircle
            // Position away from (0,0)
            val x = MathUtils.random(minPos, Gdx.graphics.width.toFloat() - minPos)
            val y = MathUtils.random(minPos, Gdx.graphics.height.toFloat() - minPos)
            val size = MathUtils.random(minSize, maxSize)
            val vx = MathUtils.random(-100f, 100f)
            val vy = MathUtils.random(-100f, 100f)
            circleData[offset] = x
            circleData[offset + 1] = y
            circleData[offset + 2] = size
            circleData[offset + 3] = 0f // pad1
            circleData[offset + 4] = MathUtils.random(0f, 1f) // r
            circleData[offset + 5] = MathUtils.random(0f, 1f) // g
            circleData[offset + 6] = MathUtils.random(0f, 1f) // b
            circleData[offset + 7] = 1f // a
            circleData[offset + 8] = if (vx == 0f) MathUtils.random(10f, 100f) else vx // Ensure non-zero velocity
            circleData[offset + 9] = if (vy == 0f) MathUtils.random(10f, 100f) else vy
            circleData[offset + 10] = 0f // pad2
            circleData[offset + 11] = 0f // pad3
            // Debug pink or static circles
            if (x < minPos || y < minPos || size > maxSize || size < minSize) {
                Gdx.app.log("CircleInit", "Invalid data at index $i: pos=($x, $y), size=$size")
            }
            if (circleData[offset + 4] > 0.8f && circleData[offset + 5] < 0.2f && circleData[offset + 6] < 0.2f) {
                Gdx.app.log("CircleInit", "Pink color detected at index $i: r=${circleData[offset + 4]}, g=${circleData[offset + 5]}, b=${circleData[offset + 6]}")
            }
        }

        // Upload initial data to SSBO
        val buffer: FloatBuffer = BufferUtils.newFloatBuffer(numCircles * floatsPerCircle)
        buffer.put(circleData)
        buffer.flip()
        Gdx.gl31.glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo)
        Gdx.gl31.glBufferData(GL_SHADER_STORAGE_BUFFER, bufferSize.toInt(), buffer, GL_DYNAMIC_DRAW)
        Gdx.gl31.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)
    }

    override fun render() {
        ScreenUtils.clear(0f, 0f, 0f, 1f)
        camera.update()
        shader.bind()
        shader.setUniformMatrix("u_projTrans", camera.combined)

        // Update circle positions and handle bouncing
        val deltaTime = Gdx.graphics.deltaTime
        val width = Gdx.graphics.width.toFloat()
        val height = Gdx.graphics.height.toFloat()
        for (i in 0 until numCircles) {
            val offset = i * floatsPerCircle
            val size = circleData[offset + 2]
            // Validate data
            if (size < minSize || size > maxSize || circleData[offset] < minPos || circleData[offset + 1] < minPos) {
                Gdx.app.log("CircleUpdate", "Invalid data at index $i: pos=(${circleData[offset]}, ${circleData[offset + 1]}), size=$size")
                circleData[offset + 2] = MathUtils.clamp(size, minSize, maxSize)
                circleData[offset] = MathUtils.clamp(circleData[offset], minPos, width - minPos)
                circleData[offset + 1] = MathUtils.clamp(circleData[offset + 1], minPos, height - minPos)
            }
            // Update position
            circleData[offset] += circleData[offset + 8] * deltaTime // x += vx * dt
            circleData[offset + 1] += circleData[offset + 9] * deltaTime // y += vy * dt
            // Bounce off walls
            if (circleData[offset] - size < minPos || circleData[offset] + size > width - minPos) {
                circleData[offset + 8] = -circleData[offset + 8]
                circleData[offset] = MathUtils.clamp(circleData[offset], minPos + size, width - minPos - size)
            }
            if (circleData[offset + 1] - size < minPos || circleData[offset + 1] + size > height - minPos) {
                circleData[offset + 9] = -circleData[offset + 9]
                circleData[offset + 1] = MathUtils.clamp(circleData[offset + 1], minPos + size, height - minPos - size)
            }
        }

        // Update SSBO
        val timeMs = measureNanoTime {
            val buffer: FloatBuffer = BufferUtils.newFloatBuffer(numCircles * floatsPerCircle)
            buffer.put(circleData)
            buffer.flip()
            Gdx.gl31.glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo)
            val dataSize = buffer.remaining() * 4
            Gdx.gl31.glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, dataSize, buffer)
            Gdx.gl31.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)

            // Render instanced
            Gdx.gl.glEnable(GL20.GL_BLEND)
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
            mesh.bind(shader)
            Gdx.gl31.glDrawArraysInstanced(GL20.GL_TRIANGLE_STRIP, 0, 4, numCircles)
            mesh.unbind(shader)
            Gdx.gl.glDisable(GL20.GL_BLEND)
        }

        println("${Gdx.graphics.framesPerSecond} fps, ${(timeMs / 1_000_000.0)} ms")
    }

    override fun dispose() {
        mesh.dispose()
        shader.dispose()
        if (ssbo != 0) {
            val ssboBuffer: IntBuffer = BufferUtils.newIntBuffer(1)
            ssboBuffer.put(ssbo)
            ssboBuffer.flip()
            Gdx.gl31.glDeleteBuffers(1, ssboBuffer)
        }
    }
}
