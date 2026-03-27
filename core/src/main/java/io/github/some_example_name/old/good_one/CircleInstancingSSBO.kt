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
    private var circlesSSBO = 0
    private val numCircles = 1_000

    // SSBO struct for circles: pos.x (4), pos.y (4), size (4), color (uint, 4) -> 16 bytes
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

        // Load shaders
        val vertexShader = """
            #version 320 es
            precision highp float;
            layout(location = 0) in vec2 a_position;

            struct Circle {
                vec2 pos;
                float size;
                uint color; // packed RGBA
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

        // Setup Circles SSBO (binding 0)
        val ssboBuffer: IntBuffer = BufferUtils.newIntBuffer(1)
        Gdx.gl31.glGenBuffers(1, ssboBuffer)
        circlesSSBO = ssboBuffer.get(0)
        Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, circlesSSBO)
        val circlesBufferSize = numCircles * bytesPerCircle.toLong()  // Bytes
        Gdx.gl31.glBufferData(GL31.GL_SHADER_STORAGE_BUFFER, circlesBufferSize.toInt(), null, GL20.GL_DYNAMIC_DRAW)
        Gdx.gl31.glBindBufferBase(GL31.GL_SHADER_STORAGE_BUFFER, 0, circlesSSBO)
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
            sizes[i] = MathUtils.random(5f, 50f)
        }

        // Pack circles data into ByteBuffer
        val circlesBuffer: ByteBuffer = BufferUtils.newByteBuffer(numCircles * bytesPerCircle)
        for (i in 0 until numCircles) {
            val x = positions[i].x
            val y = positions[i].y
            val size = sizes[i]
            val r = MathUtils.random(0f, 1f)
            val g = MathUtils.random(0f, 1f)
            val b = MathUtils.random(0f, 1f)
            val a = 1f
            val packedColor = ((a * 255f).toInt() shl 24) or
                ((b * 255f).toInt() shl 16) or
                ((g * 255f).toInt() shl 8) or
                (r * 255f).toInt()
            circlesBuffer.putFloat(x)
            circlesBuffer.putFloat(y)
            circlesBuffer.putFloat(size)
            circlesBuffer.putInt(packedColor)
        }
        circlesBuffer.flip()

        val timeMs = measureNanoTime {
            // Update Circles SSBO
            Gdx.gl31.glBindBuffer(GL31.GL_SHADER_STORAGE_BUFFER, circlesSSBO)
            val circlesDataSize = circlesBuffer.remaining()  // bytes
            Gdx.gl31.glBufferSubData(GL31.GL_SHADER_STORAGE_BUFFER, 0, circlesDataSize, circlesBuffer)
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

    override fun dispose() {
        mesh.dispose()
        shader.dispose()
        val ssboBuffer: IntBuffer = BufferUtils.newIntBuffer(1)
        ssboBuffer.put(circlesSSBO)
        ssboBuffer.flip()
        Gdx.gl31.glDeleteBuffers(1, ssboBuffer)
    }
}
