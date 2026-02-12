package io.github.some_example_name.old.experiments

import com.badlogic.gdx.Application
import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.GL30
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.viewport.StretchViewport
import kotlin.random.Random

class GenomeStageReplayStructure(cellMaxAmount: Int) {
    var x = FloatArray(cellMaxAmount)
    var y = FloatArray(cellMaxAmount)
    var colorR = FloatArray(cellMaxAmount) { 1f }
    var colorG = FloatArray(cellMaxAmount) { 1f }
    var colorB = FloatArray(cellMaxAmount) { 1f }
}

class CircleRendererExample : ApplicationAdapter() {
    private lateinit var camera: OrthographicCamera
    private lateinit var viewport: StretchViewport
    private lateinit var shader: ShaderProgram
    private lateinit var mesh: Mesh
    private lateinit var structure: GenomeStageReplayStructure
    private var cellAmount = 0 // Actual number of circles to render

    // Shaders as strings
    private val vertexShader = """
        #version 300 es
        in vec2 a_position;
        in vec3 a_color;
        out vec3 v_color;
        uniform mat4 u_projTrans;
        uniform float u_pointSize;
        void main() {
            gl_Position = u_projTrans * vec4(a_position, 0.0, 1.0);
            gl_PointSize = u_pointSize;
            v_color = a_color;
        }
    """.trimIndent()

    private val fragmentShader = """
        #version 300 es
        precision mediump float;
        in vec3 v_color;
        out vec4 FragColor;
        void main() {
            vec2 coord = gl_PointCoord - vec2(0.5, 0.5);
            float dist = dot(coord, coord);
            if (dist > 0.25) discard;
            FragColor = vec4(v_color, 1.0);
        }
    """.trimIndent()

    override fun create() {
        // Setup camera and viewport for world 960x960, stretched to screen
        val worldSize = 960f
        camera = OrthographicCamera()
        viewport = StretchViewport(worldSize, worldSize, camera)
        viewport.update(Gdx.graphics.width, Gdx.graphics.height, true)

        // Enable program point size on desktop
//        if (Gdx.app.type == Application.ApplicationType.Desktop) {
//            Gdx.gl.glEnable(GL20.GL_PROGRAM_POINT_SIZE)
//        }

        // Compile shader
        shader = ShaderProgram(vertexShader, fragmentShader)
        if (!shader.isCompiled) {
            throw RuntimeException("Shader compilation failed: ${shader.log}")
        }

        // Example structure with max 10000 cells
        val cellMaxAmount = 5000
        structure = GenomeStageReplayStructure(cellMaxAmount)

        // Fill with dummy data for example (random positions and colors)
        cellAmount = 5000 // Render 5000 circles for example
        for (i in 0 until cellAmount) {
            structure.x[i] = (Random.nextDouble() * worldSize).toFloat()
            structure.y[i] = (Random.nextDouble() * worldSize).toFloat()
            structure.colorR[i] = 1f // Set to red for visibility
            structure.colorG[i] = 0f
            structure.colorB[i] = 0f
        }

        // Create mesh for points
        val attributes = VertexAttributes(
            VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position"),
            VertexAttribute(VertexAttributes.Usage.ColorUnpacked, 3, "a_color")
        )
        mesh = Mesh(true, cellMaxAmount, 0, attributes)

        // Pack data into vertices array
        val vertices = FloatArray(cellAmount * 5)
        for (i in 0 until cellAmount) {
            val offset = i * 5
            vertices[offset] = structure.x[i]
            vertices[offset + 1] = structure.y[i]
            vertices[offset + 2] = structure.colorR[i]
            vertices[offset + 3] = structure.colorG[i]
            vertices[offset + 4] = structure.colorB[i]
        }
        mesh.setVertices(vertices, 0, cellAmount * 5)
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
    }

    override fun render() {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        viewport.apply()

        // Compute point size in pixels for world diameter (e.g., 20 world units diameter)
        val worldDiameter = 20f // Adjust this for circle size in world units
        val pixelsPerUnit = Gdx.graphics.height.toFloat() / viewport.worldHeight
        val pointSize = worldDiameter * pixelsPerUnit

        // Render
        shader.bind()
        shader.setUniformMatrix("u_projTrans", camera.combined)
        shader.setUniformf("u_pointSize", pointSize)
        mesh.render(shader, GL20.GL_POINTS, 0, cellAmount)
    }

    override fun dispose() {
        shader.dispose()
        mesh.dispose()
    }
}
