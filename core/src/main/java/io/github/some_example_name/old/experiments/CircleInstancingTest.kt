package io.github.some_example_name.old.experiments


// This is a corrected simple example for LibGDX in Kotlin demonstrating instanced rendering of circles using SSBO.
// Note: This targets Android with GLES 3.2 support (ensure device supports it).
// SSBO requires OpenGL ES 3.1+, set in config. If fails, fallback to texture-based data transfer (commented out alternative).
// Build with LibGDX 1.12+ for ES 3.1/3.2 support.
// Add to build.gradle(kts): dependencies { implementation("com.badlogicgames.gdx:gdx-backend-android:$gdxVersion") ... }

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.GL20.GL_DYNAMIC_DRAW
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
import kotlin.system.measureTimeMillis

class CircleInstancingTest : ApplicationAdapter() {

    private lateinit var camera: OrthographicCamera
    private lateinit var shader: ShaderProgram
    private lateinit var mesh: com.badlogic.gdx.graphics.Mesh
    private var ssbo = 0
    private val numCircles = 100_000
    private val circleWidth = 100 // For texture fallback, sqrt(numCircles) ~100

    // SSBO struct size: with std430 padding between float size (offset 8-12) and vec4 color (needs offset multiple of 16, so pad 12-16)
    // Floats: pos.x, pos.y (0-8), size (8-12), pad (12-16), color.r,g,b,a (16-32) -> 8 floats per circle (32 bytes)
    private val floatsPerCircle = 8

    override fun create() {
        // Setup camera
        camera = OrthographicCamera().apply {
            setToOrtho(false, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        }

        // Create quad mesh for circle base (normalized -1 to 1)
        val vertices = floatArrayOf(
            -1f, -1f,  // bottom-left
            1f, -1f,  // bottom-right
            -1f, 1f,  // top-left
            1f, 1f   // top-right
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

        // Load shaders (save as files or inline)
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
            uniform mat4 u_projTrans;
            void main() {
                int instID = gl_InstanceID;
                Circle c = circles[instID];
                vec2 offsetPos = a_position * c.size * 0.5 + c.pos;
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
                float dist = length(v_texCoord - vec2(0.5));
                if (dist > 0.5) discard;
                fragColor = v_color;
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
        // Allocate buffer (use GL_DYNAMIC_DRAW for frequent updates)
        val bufferSize = numCircles * floatsPerCircle * 4L // 4 bytes per float, use Long
        Gdx.gl31.glBufferData(GL_SHADER_STORAGE_BUFFER, bufferSize.toInt(), null, GL_DYNAMIC_DRAW)
        // Bind to binding point 0
        Gdx.gl31.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssbo)
        Gdx.gl31.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0) // Unbind
    }

    override fun render() {
        ScreenUtils.clear(0f, 0f, 0f, 1f)
        camera.update()
        shader.bind()
        shader.setUniformMatrix("u_projTrans", camera.combined)

        // Generate new circle data every frame (random positions, sizes, colors)
        val buffer: FloatBuffer = BufferUtils.newFloatBuffer(numCircles * floatsPerCircle)
        for (i in 0 until numCircles) {
            // Position in world space (screen coords)
            val x = MathUtils.random(-200f, 1000f)
            val y = MathUtils.random(-100f, 1000f)
            // Size 5-50
            val size = MathUtils.random(5f, 50f)
            // Random color
            val r = MathUtils.random(0f, 1f)
            val g = MathUtils.random(0f, 1f)
            val b = MathUtils.random(0f, 1f)
            val a = 1f
            // Pack: pos.x, pos.y, size, padding 0f, color.r, g, b, a
            buffer.put(x)
            buffer.put(y)
            buffer.put(size)
            buffer.put(0f)
            buffer.put(r)
            buffer.put(g)
            buffer.put(b)
            buffer.put(a)
        }
        buffer.flip()

        val timeMs = measureNanoTime {
            // Update SSBO (lightweight for CPU/GPU, ~320KB transfer for 10k circles)
            Gdx.gl31.glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo)
            val dataSize = buffer.remaining() * 4 // Size in bytes, int is fine for 320000
            Gdx.gl31.glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, dataSize, buffer)
            Gdx.gl31.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0)

            // Render instanced (efficient for GPU, single draw call for 10k+ instances)
            Gdx.gl.glEnable(GL20.GL_BLEND)
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
            mesh.bind(shader)
            Gdx.gl31.glDrawArraysInstanced(GL20.GL_TRIANGLE_STRIP, 0, 4, numCircles)
            mesh.unbind(shader)
            Gdx.gl.glDisable(GL20.GL_BLEND)
        }

        println(Gdx.graphics.framesPerSecond.toString() + " " + ((timeMs / 1_000_000.0).toDouble()))
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
