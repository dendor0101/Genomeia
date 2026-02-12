package io.github.some_example_name.old.experiments

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes.Usage
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.utils.ScreenUtils


class VoronoiDemo1 : ApplicationAdapter() {
    private var shader: ShaderProgram? = null
    private var mesh: Mesh? = null
    private lateinit var centroids: FloatArray
    private val instanceCount = 20
    private val R = 0.1f
    private val drawCenter = true
    private val style = 0

    override fun create() {
        // Enable instancing support in LibGDX
        ShaderProgram.pedantic = false

        val vertexShader = "#version 330 core\n" +
            "layout(location = 0) in vec2 in_Quad;\n" +
            "layout(location = 2) in vec2 in_Centroid;\n" +
            "out vec2 ex_Quad;\n" +
            "flat out vec3 ex_Color;\n" +
            "out vec2 ex_Centroid;\n" +
            "uniform float R;\n" +
            "vec3 color(int i){\n" +
            "    float r = float((i >> 0) & 0xff)/255.0;\n" +
            "    float g = float((i >> 8) & 0xff)/255.0;\n" +
            "    float b = float((i >>16) & 0xff)/255.0;\n" +
            "    return vec3(r,g,b);\n" +
            "}\n" +
            "void main(){\n" +
            "    ex_Centroid = in_Centroid;\n" +
            "    ex_Color = color(gl_InstanceID);\n" +
            "    ex_Quad = R*in_Quad + in_Centroid;\n" +
            "    gl_Position = vec4(ex_Quad,0.0,1.0);\n" +
            "}"

        val fragmentShader = "#version 330 core\n" +
            "in vec2 ex_Quad;\n" +
            "in vec2 ex_Centroid;\n" +
            "flat in vec3 ex_Color;\n" +
            "out vec4 fragColor;\n" +
            "uniform float R;\n" +
            "uniform bool drawcenter;\n" +
            "uniform int style;\n" +
            "void main(){\n" +
            "    float depth = length(ex_Quad - ex_Centroid);\n" +
            "    gl_FragDepth = depth;\n" +
            "    if(depth > R) discard;\n" +
            "    if(style == 1) fragColor = vec4(vec3(depth/R),1.0);\n" +
            "    else fragColor = vec4(ex_Color,1.0);\n" +
            "    if(depth < 0.1*R && drawcenter) fragColor = vec4(1.0);\n" +
            "}"

        shader = ShaderProgram(vertexShader, fragmentShader)
        if (!shader!!.isCompiled()) {
            throw RuntimeException(shader!!.getLog())
        }

        // Square for instancing (-1..1 quad, scaled in shader)
        val vertices = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            1f, 1f,
            -1f, 1f
        )

        val indices = shortArrayOf(0, 1, 2, 2, 3, 0)

        mesh = Mesh(
            true, 4, 6,
            VertexAttribute(Usage.Position, 2, "in_Quad"),
            VertexAttribute(Usage.Generic, 2, "in_Centroid") // per-instance
        )

        mesh!!.setVertices(vertices)
        mesh!!.setIndices(indices)

        // Generate centroids
        centroids = FloatArray(instanceCount * 2)
        for (i in 0..<instanceCount) {
            centroids[i * 2] = MathUtils.random(-0.8f, 0.8f)
            centroids[i * 2 + 1] = MathUtils.random(-0.8f, 0.8f)
        }
    }

    override fun render() {
        ScreenUtils.clear(0f, 0f, 0f, 1f)

        shader!!.bind()
        shader!!.setUniformf("R", R)
        shader!!.setUniformi("drawcenter", if (drawCenter) 1 else 0)
        shader!!.setUniformi("style", style)

        // Enable instanced rendering
        for (i in 0..<instanceCount) {
            mesh!!.setVertices(
                floatArrayOf(
                    -1f, -1f, centroids[i * 2], centroids[i * 2 + 1],
                    1f, -1f, centroids[i * 2], centroids[i * 2 + 1],
                    1f, 1f, centroids[i * 2], centroids[i * 2 + 1],
                    -1f, 1f, centroids[i * 2], centroids[i * 2 + 1]
                )
            )
            mesh!!.render(shader, GL20.GL_TRIANGLES)
        }
    }

    override fun dispose() {
        mesh!!.dispose()
        shader!!.dispose()
    }
}

