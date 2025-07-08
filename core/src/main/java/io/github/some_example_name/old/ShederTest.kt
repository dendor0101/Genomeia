package io.github.some_example_name.old

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.*
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.graphics.VertexAttributes.Usage
import com.badlogic.gdx.graphics.Mesh

class ShaderBall : ApplicationAdapter() {
    private lateinit var shader: ShaderProgram
    private lateinit var camera: OrthographicCamera
    private lateinit var mesh: Mesh

    private var ballPos = Vector2(400f, 300f) // Начальная позиция шара
    private var ballVelocity = Vector2(.4f, .6f) // Скорость шара
    private val ballRadius = 50f // Радиус шара

    override fun create() {
        camera = OrthographicCamera(Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        camera.setToOrtho(false)

        val vertexShader =
            """
    attribute vec4 a_position;
    void main() {
        gl_Position = a_position;
    }
""".trimIndent()

        val fragmentShader =
            """
    #ifdef GL_ES
    precision mediump float;
    #endif

    uniform float u_time;
    uniform vec2 u_resolution;
    uniform vec2 u_ballPos;
    uniform float u_radius;

    void main() {
        vec2 uv = gl_FragCoord.xy / u_resolution;
        float dist = distance(gl_FragCoord.xy, u_ballPos);
        float gradient = smoothstep(u_radius, 0.0, dist);
        vec3 color = mix(vec3(1.0, 0.0, 1.0), vec3(0.0, 1.0, 1.0), sin(u_time) * 0.5 + 0.5);
        gl_FragColor = vec4(color * gradient, 1.0);
    }
""".trimIndent()

        shader = ShaderProgram(vertexShader, fragmentShader)
        if (!shader.isCompiled) {
            Gdx.app.error("Shader", "Compilation failed:\n" + shader.log)
        }

        mesh = createCircleMesh(ballRadius, 32) // Создаем меш для шара
    }

    var time = 0f

    override fun render() {
        updateBall() // Обновляем позицию шара

        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        time += Gdx.graphics.deltaTime * 2f
        shader.bind()
        shader.setUniformf("u_time", time)
        shader.setUniformf("u_resolution", Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        shader.setUniformf("u_ballPos", ballPos)
        shader.setUniformf("u_radius", ballRadius)

        mesh.render(shader, GL20.GL_TRIANGLE_FAN) // Отрисовываем шар
    }

    private fun updateBall() {
        // Обновляем позицию шара
        ballPos.add(ballVelocity)

        // Проверяем столкновения с границами экрана
        if (ballPos.x - ballRadius < 0 || ballPos.x + ballRadius > Gdx.graphics.width) {
            ballVelocity.x *= -1 // Отскок по горизонтали
        }
        if (ballPos.y - ballRadius < 0 || ballPos.y + ballRadius > Gdx.graphics.height) {
            ballVelocity.y *= -1 // Отскок по вертикали
        }
    }

    private fun createCircleMesh(radius: Float, segments: Int): Mesh {
        val vertices = FloatArray((segments + 2) * 2)
        vertices[0] = 0f
        vertices[1] = 0f

        for (i in 0..segments) {
            val angle = (i.toFloat() / segments.toFloat()) * Math.PI * 2f
            val x = (Math.cos(angle) * radius).toFloat()
            val y = (Math.sin(angle) * radius).toFloat()
            vertices[(i + 1) * 2] = x
            vertices[(i + 1) * 2 + 1] = y
        }

        return Mesh(
            true, vertices.size / 2, 0,
            VertexAttribute(Usage.Position, 2, "a_position")
        ).apply {
            setVertices(vertices)
        }
    }

    override fun dispose() {
        shader.dispose()
        mesh.dispose()
    }
}
