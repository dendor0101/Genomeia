package io.github.some_example_name.old.good_one

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShaderProgram

class TextureDraw : ApplicationAdapter() {

    private lateinit var batch: SpriteBatch
    private lateinit var texture: Texture
    private lateinit var shader: ShaderProgram

    override fun create() {
        batch = SpriteBatch()

        // Загружаем вашу PNG-текстуру с альфа-каналом
        // Положите файл в папку assets/ (или core/assets/ в desktop-проекте)
        texture = Texture(Gdx.files.internal("texture.png")) // ← замените на имя вашего файла

        // ВЕРШИННЫЙ ШЕЙДЕР (тот же, что и в Java)
        val vertexShader = """
            uniform mat4 u_projTrans;
            attribute vec4 a_position;
            attribute vec4 a_color;
            attribute vec2 a_texCoord0;
            varying vec4 v_color;
            varying vec2 v_texCoords;
            void main() {
                v_color = a_color;
                v_texCoords = a_texCoord0;
                gl_Position = u_projTrans * a_position;
            }
        """.trimIndent()

        // ФРАГМЕНТНЫЙ ШЕЙДЕР — полностью поддерживает альфа-канал
        val fragmentShader = """
            varying vec4 v_color;
            varying vec2 v_texCoords;
            uniform sampler2D u_texture;
            void main() {
                vec4 color = texture2D(u_texture, v_texCoords);
                gl_FragColor = v_color * color;  // alpha-канал автоматически применяется
            }
        """.trimIndent()

        shader = ShaderProgram(vertexShader, fragmentShader)

        // Проверяем ошибки компиляции шейдера
        if (!shader.isCompiled) {
            Gdx.app.error("Shader", shader.log)
        }

        // Включаем смешивание по альфа-каналу (важно для PNG с прозрачностью)
        batch.enableBlending()
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
    }

    override fun render() {
        // Очищаем экран
        Gdx.gl.glClearColor(0.1f, 0.1f, 0.1f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        // Включаем наш шейдер
        batch.shader = shader

        batch.begin()
        // Рисуем текстуру на весь экран (256×256 растягивается автоматически)
        batch.draw(
            texture,
            0f, 0f,
            Gdx.graphics.width.toFloat(),
            Gdx.graphics.height.toFloat()
        )
        batch.end()

        // Возвращаем стандартный шейдер
        batch.shader = null
    }

    override fun dispose() {
        batch.dispose()
        texture.dispose()
        shader.dispose()
    }
}
