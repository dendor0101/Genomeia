package io.github.some_example_name.render.microscope

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL30.*
import com.badlogic.gdx.utils.Disposable

/** Линии поверх готового изображения: оптика и размытие не скрывают ошибки сетки. */
internal class TissueSpringOverlay:Disposable {
    private val program=OpticalProgram(
        Gdx.files.internal("shaders/microscope/vs_tissue_springs.vert").readString(),
        Gdx.files.internal("shaders/microscope/fs_tissue_springs.frag").readString(),true)
    fun render(mesh:TissueIslandMeshes,liveData:Int,s:OpticalState){
        val gl=Gdx.gl30
        gl.glDisable(GL_DEPTH_TEST);gl.glDisable(GL_SCISSOR_TEST);gl.glDisable(GL_CULL_FACE)
        gl.glEnable(GL_BLEND);gl.glBlendEquation(GL_FUNC_ADD);gl.glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA)
        gl.glLineWidth(1f);program.use();program.texture("uLiveData",liveData,6)
        program.v2("uCam",s.camX.toFloat(),s.camY.toFloat())
        program.v2("uSpan",(s.width*s.scale).toFloat(),(s.height*s.scale).toFloat())
        mesh.drawSprings(program)
        gl.glDisable(GL_BLEND);gl.glActiveTexture(GL_TEXTURE0)
    }
    override fun dispose(){program.dispose()}
}
