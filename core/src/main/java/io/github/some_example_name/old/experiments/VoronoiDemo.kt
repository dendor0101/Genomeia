package io.github.some_example_name.old.experiments

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.*
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.math.Vector2
import kotlin.random.Random

class VoronoiDemo : ApplicationAdapter() {
    private lateinit var mesh: Mesh
    private lateinit var seedTex: Texture
    private lateinit var jfaShader: ShaderProgram
    private lateinit var voronoiShader: ShaderProgram
    private lateinit var fbo1: FrameBuffer
    private lateinit var fbo2: FrameBuffer

    private val width = 512
    private val height = 512
    private val points = mutableListOf<Vector2>()

    override fun create() {
        ShaderProgram.pedantic = false

        // Fullscreen quad
        mesh = Mesh(true, 4, 6,
            VertexAttribute(VertexAttributes.Usage.Position, 2, "in_Position")
        )
        mesh.setVertices(floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f,  1f,
            1f,  1f
        ))
        mesh.setIndices(shortArrayOf(0, 1, 2, 2, 1, 3))

        // Generate random points
        repeat(100) {
            points += Vector2(Random.nextFloat() * width, Random.nextFloat() * height)
        }

        // Create seed texture
        val pixmap = Pixmap(width, height, Pixmap.Format.RGBA8888)
        pixmap.setColor(0f, 0f, 0f, 1f)
        pixmap.fill()
        for (p in points) {
            pixmap.drawPixel(
                p.x.toInt(), p.y.toInt(),
                Color.rgba8888(p.x / width, p.y / height, 0f, 1f)
            )
        }
        seedTex = Texture(pixmap)
        pixmap.dispose()

        // Shaders
        jfaShader = ShaderProgram(fullscreenVert, jfaFrag)
        if (!jfaShader.isCompiled) Gdx.app.error("JFA", jfaShader.log)

        voronoiShader = ShaderProgram(fullscreenVert, voronoiFrag)
        if (!voronoiShader.isCompiled) Gdx.app.error("Voronoi", voronoiShader.log)

        // FBOs
        fbo1 = FrameBuffer(Pixmap.Format.RGBA8888, width, height, false)
        fbo2 = FrameBuffer(Pixmap.Format.RGBA8888, width, height, false)
    }

    override fun render() {
        var src: Texture = seedTex
        var dst: FrameBuffer = fbo1

        // Основные проходы JFA
        var step = width / 2
        while (step >= 1) {
            dst.begin()
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            jfaShader.bind()
            jfaShader.setUniformi("uPrev", 0)
            jfaShader.setUniformf("uResolution", width.toFloat(), height.toFloat())
            jfaShader.setUniformf("uStep", step.toFloat())
            src.bind(0)
            mesh.render(jfaShader, GL20.GL_TRIANGLES)
            dst.end()

            src = dst.colorBufferTexture
            dst = if (dst === fbo1) fbo2 else fbo1
            step /= 2
        }

        // Финальный pass uStep=1 для точности
        dst.begin()
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        jfaShader.bind()
        jfaShader.setUniformi("uPrev", 0)
        jfaShader.setUniformf("uResolution", width.toFloat(), height.toFloat())
        jfaShader.setUniformf("uStep", 1f)
        src.bind(0)
        mesh.render(jfaShader, GL20.GL_TRIANGLES)
        dst.end()

        val finalTex = dst.colorBufferTexture

        // Рендер Voronoi карты
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        voronoiShader.bind()
        voronoiShader.setUniformi("uSeeds", 0)
        voronoiShader.setUniformf("uResolution", width.toFloat(), height.toFloat())
        finalTex.bind(0)
        mesh.render(voronoiShader, GL20.GL_TRIANGLES)
    }

    override fun dispose() {
        mesh.dispose()
        seedTex.dispose()
        jfaShader.dispose()
        voronoiShader.dispose()
        fbo1.dispose()
        fbo2.dispose()
    }

    // ---------------- SHADERS ----------------

    private val fullscreenVert = """
        #version 300 es
        precision highp float;
        layout(location=0) in vec2 in_Position;
        out vec2 v_TexCoord;
        void main() {
            v_TexCoord = (in_Position + 1.0) * 0.5;
            gl_Position = vec4(in_Position, 0.0, 1.0);
        }
    """.trimIndent()

    private val jfaFrag = """
        #version 300 es
        precision highp float;
        in vec2 v_TexCoord;
        out vec4 fragColor;
        uniform sampler2D uPrev;
        uniform float uStep;
        uniform vec2 uResolution;

        vec2 loadSeed(vec2 uv) {
            vec4 d = texture(uPrev, uv);
            return d.xy * uResolution;
        }

        float dist2(vec2 a, vec2 b) {
            vec2 d = a - b;
            return dot(d, d);
        }

        void main() {
            vec2 pixel = v_TexCoord * uResolution;
            vec2 bestSeed = loadSeed(v_TexCoord);
            float bestDist = (bestSeed.x < 0.0) ? 1e20 : dist2(pixel, bestSeed);

            // Основной проход
            for (int y=-1; y<=1; ++y) {
                for (int x=-1; x<=1; ++x) {
                    vec2 offset = vec2(float(x), float(y)) * uStep;
                    vec2 neighborUV = (pixel + offset) / uResolution;
                    if (neighborUV.x < 0.0 || neighborUV.y < 0.0 ||
                        neighborUV.x > 1.0 || neighborUV.y > 1.0) continue;
                    vec2 seed = loadSeed(neighborUV);
                    if (seed.x < 0.0) continue;
                    float d = dist2(pixel, seed);
                    if (d < bestDist) {
                        bestDist = d;
                        bestSeed = seed;
                    }
                }
            }

            // Финальный точный pass uStep=1
            if(uStep == 1.0) {
                for(int y=-1; y<=1; ++y) {
                    for(int x=-1; x<=1; ++x) {
                        if(x==0 && y==0) continue;
                        vec2 offset = vec2(float(x), float(y));
                        vec2 neighborUV = (pixel + offset) / uResolution;
                        if(neighborUV.x < 0.0 || neighborUV.y < 0.0 ||
                           neighborUV.x > 1.0 || neighborUV.y > 1.0) continue;
                        vec2 seed = loadSeed(neighborUV);
                        if(seed.x < 0.0) continue;
                        float d = dist2(pixel, seed);
                        if(d < bestDist) {
                            bestDist = d;
                            bestSeed = seed;
                        }
                    }
                }
            }

            fragColor = vec4(bestSeed / uResolution, 0.0, 1.0);
        }
    """.trimIndent()

    private val voronoiFrag = """
        #version 300 es
        precision highp float;
        in vec2 v_TexCoord;
        out vec4 fragColor;
        uniform sampler2D uSeeds;
        uniform vec2 uResolution;

        vec3 hashColor(vec2 seed) {
            float x = seed.x * 37.0 + seed.y * 17.0;
            return vec3(fract(sin(x*0.1)*43758.5),
                        fract(sin(x*0.2)*12345.6),
                        fract(sin(x*0.3)*98765.4));
        }

        void main() {
            vec2 seed = texture(uSeeds, v_TexCoord).xy * uResolution;
            if (seed.x < 0.0) {
                fragColor = vec4(0.0);
            } else {
                fragColor = vec4(hashColor(seed), 1.0);
            }
        }
    """.trimIndent()
}
