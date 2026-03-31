package io.github.some_example_name.old.good_one

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
import java.nio.ByteBuffer
import java.nio.IntBuffer
import kotlin.system.measureNanoTime

class CircleInstancingSSBO : ApplicationAdapter() {

    private lateinit var camera: OrthographicCamera
    private lateinit var shader: ShaderProgram
    private lateinit var mesh: com.badlogic.gdx.graphics.Mesh

    private val ssbos = IntArray(2) { 0 }           // 0 = A, 1 = B
    private var currentWriteIndex = 0               // какой буфер обновляем сейчас
    private val numCircles = 1_000
    private val bytesPerCircle = 16

    // Temporary storage for circle data
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

        // === Шейдеры с ping-pong ===
        val vertexShader = """
            #version 320 es
            precision highp float;

            layout(location = 0) in vec2 a_position;

            struct Circle {
                vec2 pos;
                float size;
                uint color;
            };

            layout(std430, binding = 0) buffer CirclesA {
                Circle circlesA[];
            };

            layout(std430, binding = 1) buffer CirclesB {
                Circle circlesB[];
            };

            uniform uint u_currentBuffer;   // 0 = A, 1 = B

            out vec2 v_texCoord;
            out vec4 v_color;
            uniform mat4 u_projTrans;

            void main() {
                int instID = gl_InstanceID;
                Circle c;

                if (u_currentBuffer == 0u) {
                    c = circlesA[instID];
                } else {
                    c = circlesB[instID];
                }

                vec2 offsetPos = a_position * c.size * 0.5 + c.pos;
                v_texCoord = a_position * 0.5 + 0.5;
                v_color = unpackUnorm4x8(c.color);

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
                float dist_norm = length(v_texCoord - vec2(0.5));
                if (dist_norm > 0.5) discard;
                fragColor = v_color;
            }
        """.trimIndent()

        shader = ShaderProgram(vertexShader, fragmentShader).apply {
            if (!isCompiled) {
                Gdx.app.log("Shader", "Compilation error: ${log}")
                Gdx.app.exit()
            }
        }

        // === Создаём два SSBO ===
        val ssboBuffer = BufferUtils.newIntBuffer(2)
        Gdx.gl31.glGenBuffers(2, ssboBuffer)
        ssbos[0] = ssboBuffer.get(0)
        ssbos[1] = ssboBuffer.get(1)

        val bufferSize = (numCircles * bytesPerCircle).toLong()

        for (i in 0..1) {
            Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, ssbos[i])
            Gdx.gl31.glBufferData(GL31.GL_SHADER_STORAGE_BUFFER, bufferSize.toInt(), null, GL20.GL_DYNAMIC_DRAW)
            Gdx.gl31.glBindBufferBase(GL31.GL_SHADER_STORAGE_BUFFER, i, ssbos[i]) // binding 0 и 1
        }

        Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, 0)
    }

    override fun render() {
        ScreenUtils.clear(0f, 0f, 0f, 1f)
        camera.update()

        // Генерируем новые данные
        for (i in 0 until numCircles) {
            positions[i].set(
                MathUtils.random(0f, Gdx.graphics.width.toFloat()),
                MathUtils.random(0f, Gdx.graphics.height.toFloat())
            )
            sizes[i] = MathUtils.random(5f, 50f)
        }

        // Подготавливаем данные в ByteBuffer
        val circlesBuffer = BufferUtils.newByteBuffer(numCircles * bytesPerCircle)
        for (i in 0 until numCircles) {
            val x = positions[i].x
            val y = positions[i].y
            val size = sizes[i]
            val r = MathUtils.random(0f, 1f)
            val g = MathUtils.random(0f, 1f)
            val b = MathUtils.random(0f, 1f)

            val packedColor = ((255 shl 24) or
                ((b * 255).toInt() shl 16) or
                ((g * 255).toInt() shl 8) or
                (r * 255).toInt())

            circlesBuffer.putFloat(x)
            circlesBuffer.putFloat(y)
            circlesBuffer.putFloat(size)
            circlesBuffer.putInt(packedColor)
        }
        circlesBuffer.flip()

        val writeIndex = currentWriteIndex
        val timeMs = measureNanoTime {
            val readIndex = 1 - currentWriteIndex   // ping-pong

            // === 1. Обновляем буфер, в который сейчас пишем ===
            Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, ssbos[writeIndex])
            Gdx.gl31.glBufferSubData(GL31.GL_SHADER_STORAGE_BUFFER, 0, circlesBuffer.remaining(), circlesBuffer)
            Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, 0)

            // === 2. Рендерим, читая из другого буфера ===
            shader.bind()
            shader.setUniformMatrix("u_projTrans", camera.combined)
            shader.setUniformi("u_currentBuffer", readIndex)   // 0 или 1

            Gdx.gl.glEnable(GL20.GL_BLEND)
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

            mesh.bind(shader)
            Gdx.gl31.glDrawArraysInstanced(GL20.GL_TRIANGLE_STRIP, 0, 4, numCircles)
            mesh.unbind(shader)

            Gdx.gl.glDisable(GL20.GL_BLEND)

            // Переключаем для следующего кадра
            currentWriteIndex = readIndex
        }

        println("${Gdx.graphics.framesPerSecond}  ${timeMs / 1_000_000.0} ms ${ssbos[writeIndex]}")
        Thread.sleep(100) // лучше убрать или сделать опциональным
    }

    override fun dispose() {
        mesh.dispose()
        shader.dispose()

        val deleteBuffer = BufferUtils.newIntBuffer(2).apply {
            put(ssbos[0])
            put(ssbos[1])
            flip()
        }
        Gdx.gl31.glDeleteBuffers(2, deleteBuffer)
    }
}
