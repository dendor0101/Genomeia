package io.github.some_example_name.old.systems.render.opengles30

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.GL20.GL_FLOAT
import com.badlogic.gdx.graphics.GL20.GL_RGBA
import com.badlogic.gdx.graphics.GL20.GL_TEXTURE_2D
import com.badlogic.gdx.graphics.GL30.GL_RGBA32F
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.TextureData
import com.badlogic.gdx.utils.GdxRuntimeException
import java.nio.FloatBuffer

class FloatTexture2D(val widthSize: Int, val heightSize: Int) {
    val texture: Texture

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
                    target, 0, GL_RGBA32F, widthSize, heightSize, 0,
                    GL_RGBA, GL_FLOAT, null
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

    fun update(bufferFloat: FloatBuffer) {

        texture.bind()

        Gdx.gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, 1)
        //При некоторых обстаятельствах очень долго выполняется до 16 мс
        Gdx.gl.glTexSubImage2D(
            GL_TEXTURE_2D, 0, 0, 0,
            widthSize, heightSize,
            GL_RGBA, GL_FLOAT, bufferFloat
        )
    }

    fun dispose() {
        texture.dispose()
    }
}
