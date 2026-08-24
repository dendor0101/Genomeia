package io.github.some_example_name.render.components

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.glutils.ShaderProgram

/**
 * Applies Sobel edge detection and pastel stylization post-processing effect.
 * Reads from scene FBO, outputs to blur FBO.
 */
class PostProcessRenderer : RenderComponent {

    private lateinit var sobelShader: ShaderProgram

    override fun create() {
        val vertexShader = Gdx.files.internal("shaders/post_process/post_process.vert").readString()
        val fragmentShader = Gdx.files.internal("shaders/post_process/post_process.frag").readString()

        sobelShader = ShaderProgram(vertexShader, fragmentShader)
        if (!sobelShader.isCompiled) {
            throw RuntimeException("Sobel shader compilation failed: ${sobelShader.log}")
        }
    }

    override fun resize(width: Int, height: Int) {
        // No size-dependent resources in this component
    }

    override fun render(context: RenderContext) {
        if (!context.usePostProcess) return

        val inputTexture = context.currentTexture ?: return
        val outputFbo = context.blurFbo ?: return
        val sceneFbo = context.sceneFbo ?: return

        outputFbo.begin()

        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
        Gdx.gl.glDisable(GL20.GL_BLEND)

        sobelShader.bind()
        sobelShader.setUniformi("u_texture", 0)
        sobelShader.setUniformf("u_resolution", sceneFbo.width.toFloat(), sceneFbo.height.toFloat())

        val zoomX10 = context.zoom * 10f
        val sobel = when {
            zoomX10 < 0.16f -> 0.16f
            zoomX10 > 0.24f -> 0.24f
            else -> zoomX10
        }
        sobelShader.setUniformf("u_zoom", sobel)

        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)
        inputTexture.bind()

        context.fullscreenMesh.bind(sobelShader)
        Gdx.gl.glDrawArrays(GL20.GL_TRIANGLE_STRIP, 0, 4)
        context.fullscreenMesh.unbind(sobelShader)

        outputFbo.end()

        // Pass output texture to next component
        context.currentTexture = outputFbo.colorBufferTexture
    }

    override fun dispose() {
        sobelShader.dispose()
    }
}