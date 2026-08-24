package io.github.some_example_name.render.components

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.glutils.ShaderProgram

/**
 * Applies chromatic aberration distortion post-processing effect.
 * Reads from blur FBO, outputs to distort FBO.
 */
class DistortRenderer : RenderComponent {

    private lateinit var distortShader: ShaderProgram

    override fun create() {
        val vert = Gdx.files.internal("shaders/blur/blur.vert").readString()
        val frag = Gdx.files.internal("shaders/blur/ca_distort.frag").readString()
        distortShader = ShaderProgram(vert, frag)
        if (!distortShader.isCompiled) {
            throw RuntimeException("Distort shader failed: ${distortShader.log}")
        }
    }

    override fun resize(width: Int, height: Int) {
        // No size-dependent resources in this component
    }

    override fun render(context: RenderContext) {
        if (!context.usePostProcess) return

        val inputTexture = context.currentTexture ?: return
        val outputFbo = context.distortFbo ?: return
        val blurFbo = context.blurFbo ?: return

        outputFbo.begin()

        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
        Gdx.gl.glDisable(GL20.GL_BLEND)

        distortShader.bind()
        distortShader.setUniformi("u_texture", 0)
        distortShader.setUniformf("u_resolution", blurFbo.width.toFloat(), blurFbo.height.toFloat())

        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)
        inputTexture.bind()

        context.fullscreenMesh.bind(distortShader)
        Gdx.gl.glDrawArrays(GL20.GL_TRIANGLE_STRIP, 0, 4)
        context.fullscreenMesh.unbind(distortShader)

        outputFbo.end()

        // Pass output texture to next component
        context.currentTexture = outputFbo.colorBufferTexture
    }

    override fun dispose() {
        distortShader.dispose()
    }
}
