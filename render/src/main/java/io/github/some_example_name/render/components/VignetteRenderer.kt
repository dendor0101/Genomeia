package io.github.some_example_name.render.components

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.glutils.ShaderProgram

/**
 * Applies vignette effect as the final post-processing pass.
 * Renders directly to screen.
 */
class VignetteRenderer : RenderComponent {

    private lateinit var vignetteShader: ShaderProgram

    override fun create() {
        val vertexShader = Gdx.files.internal("shaders/post_process/post_process.vert").readString()
        val fragmentShader = Gdx.files.internal("shaders/vignette/vignette.frag").readString()

        vignetteShader = ShaderProgram(vertexShader, fragmentShader)
        if (!vignetteShader.isCompiled) {
            throw RuntimeException("Vignette shader compilation failed: ${vignetteShader.log}")
        }
    }

    override fun resize(width: Int, height: Int) {
        // No size-dependent resources in this component
    }

    override fun render(context: RenderContext) {
        if (!context.usePostProcess) return

        val inputTexture = context.currentTexture ?: return
        val blurFbo = context.blurFbo ?: return

        // Final pass renders directly to screen (no FBO begin)

        vignetteShader.bind()
        vignetteShader.setUniformi("u_texture", 0)
        vignetteShader.setUniformf("u_resolution", blurFbo.width.toFloat(), blurFbo.height.toFloat())
        vignetteShader.setUniformf("u_vignetteEnabled", context.vignetteEnabled)

        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)
        inputTexture.bind()

        context.fullscreenMesh.bind(vignetteShader)
        Gdx.gl.glDrawArrays(GL20.GL_TRIANGLE_STRIP, 0, 4)
        context.fullscreenMesh.unbind(vignetteShader)
    }

    override fun dispose() {
        vignetteShader.dispose()
    }
}