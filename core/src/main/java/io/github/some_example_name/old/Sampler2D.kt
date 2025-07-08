package io.github.some_example_name.old

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.*
import com.badlogic.gdx.graphics.GL20.*
import com.badlogic.gdx.graphics.GL30.GL_RED
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.GdxRuntimeException
import kotlin.random.Random

class FloatTexture2D(val widthSize: Int, val heightSize: Int) {
    val texture: Texture
    private val buffer = BufferUtils.newFloatBuffer(widthSize * heightSize * 4)

    init {
        val texData = object : TextureData {
            override fun isPrepared() = true
            override fun prepare() {}
            override fun consumePixmap(): Pixmap {
                throw GdxRuntimeException("This TextureData implementation does not return a Pixmap")
            }

            override fun disposePixmap() = false
            override fun consumeCustomData(target: Int) {
                Gdx.gl.glTexImage2D(
                    target, 0, GL30.GL_RGBA16F, widthSize, heightSize, 0,
                    GL_RGBA, GL30.GL_FLOAT, null
                )
            }

            override fun getWidth() = widthSize
            override fun getHeight() = heightSize
            override fun getFormat() = Pixmap.Format.RGBA8888
            override fun getType() = TextureData.TextureDataType.Custom
            override fun useMipMaps() = false
            override fun isManaged() = false
        }

        texture = Texture(texData)
        texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
        texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge)
    }

    fun update(data: FloatArray) {
        require(data.size == widthSize * heightSize * 4) { "Invalid data size" }
        buffer.clear()
        buffer.put(data)
        buffer.position(0)

        texture.bind()
        Gdx.gl.glTexSubImage2D(
            GL_TEXTURE_2D, 0, 0, 0,
            widthSize, heightSize,
            GL_RGBA, GL30.GL_FLOAT, buffer
        )
    }
}


class MyGdxGame : ApplicationAdapter() {
    private lateinit var shader: ShaderProgram
    private lateinit var mesh: Mesh
    private lateinit var floatTexture: FloatTexture2D

    private val width = 512
    private val height = 512

    override fun create() {
        // GLSL
        val vertex = """
            attribute vec4 a_position;
            attribute vec2 a_texCoord0;
            varying vec2 v_texCoord;
            void main() {
                v_texCoord = a_texCoord0;
                gl_Position = a_position;
            }
        """

        val fragment = """
            #version 300 es
            precision mediump float;
            precision highp sampler2D;

            in vec2 v_texCoord;
            out vec4 fragColor;
            uniform sampler2D u_floatTexture;

            float getFloat(int index) {
                int pixelIndex = index / 4;
                int component = index % 4;

                ivec2 texCoord = ivec2(pixelIndex % 512, pixelIndex / 512);
                vec4 texel = texelFetch(u_floatTexture, texCoord, 0);

                return component == 0 ? texel.r :
                       component == 1 ? texel.g :
                       component == 2 ? texel.b : texel.a;
            }

            void main() {
                ivec2 screenCoord = ivec2(v_texCoord * vec2(3440.0, 1200.0));
                int linearIndex = screenCoord.y * 1024 + screenCoord.x;
                float result = 0.0;
                for (int y = 0; y <= 14000; y++) {
                    result += distance(vec2(1234.34), vec2(18834.341));
                }
                float value = getFloat(linearIndex);
                fragColor = vec4(vec3(value), float((result - 100.0) / result)); // оттенок серого
            }
        """

        ShaderProgram.pedantic = false
        shader = ShaderProgram(vertex, fragment)
        require(shader.isCompiled) { shader.log }

        // Создаем mesh для отрисовки полноэкранного квадрата
        mesh = Mesh(true, 4, 6,
            VertexAttribute.Position(),
            VertexAttribute.TexCoords(0)
        )
        mesh.setVertices(floatArrayOf(
            -1f, -1f, 0f, 0f, 0f,   // pos + uv
            1f, -1f, 0f, 1f, 0f,
            1f,  1f, 0f, 1f, 1f,
            -1f,  1f, 0f, 0f, 1f
        ))
        mesh.setIndices(shortArrayOf(0, 1, 2, 2, 3, 0))

        // Создаём текстуру
        floatTexture = FloatTexture2D(width, height)
    }

    override fun render() {
        var counter = 0
        var deliter = Random.nextInt(20) + 1
        val data = FloatArray(width * height * 4) {
            counter++
            if (counter % deliter * 2 == 0) 1f else (counter % deliter) / deliter.toFloat()
//            counter / 262144f
        } // RGBA случайно
        val start = System.nanoTime()
        floatTexture.update(data)
        val end = System.nanoTime()
        println("Method took ${(end - start) / 1_000_000.0} ms ${Gdx.graphics.framesPerSecond}")

        Gdx.gl.glClear(GL_COLOR_BUFFER_BIT)
        shader.bind()
        floatTexture.texture.bind(0)
        shader.setUniformi("u_floatTexture", 0)

        mesh.render(shader, GL_TRIANGLES)
    }

    override fun dispose() {
        shader.dispose()
        mesh.dispose()
        floatTexture.texture.dispose()
    }
}
