package io.github.some_example_name.render.microscope

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.render.RenderFrame
import kotlin.math.*

/** Рендер живого снимка: ткань, оптика и камера микроскопа. */
class TissueMicroscopeRenderer(private val materials:IntArray):Disposable {
    private var graph:OpticalPipeline?=null
    private var source:LiveOpticalScene?=null
    private var state:OpticalState?=null
    private var settings=MicroscopeSettings()
    private val focus=MicroscopeFocus()
    private var oldX=Double.NaN;private var oldY=Double.NaN;private var oldScale=Double.NaN
    private val still=OpticalStillFrame()
    private var springs:TissueSpringOverlay?=null
    private var previousTime=Float.NaN
    fun render(frame:RenderFrame,output:FrameBuffer?,width:Int,height:Int){
        if(graph==null){graph=OpticalPipeline();state=OpticalState(graph!!,settings);source=LiveOpticalScene(materials)
            Gdx.app.log("TissueMicroscope","GL_RENDERER=${Gdx.gl.glGetString(com.badlogic.gdx.graphics.GL20.GL_RENDERER)}")}
        val desired=frame.opticalSettings
        if(desired!=null&&desired!==settings){settings=desired;state=OpticalState(graph!!,settings);focus.reset();oldX=Double.NaN;oldScale=Double.NaN;previousTime=Float.NaN;still.invalidate()}
        val s=state!!;val live=source!!;val m=frame.cameraProjection.`val`
        s.width=width;s.height=height
        s.scale=abs(2.0/m[Matrix4.M00]/width)*LiveOpticalScene.UNITS
        s.camX=(-m[Matrix4.M03]/m[Matrix4.M00]*LiveOpticalScene.UNITS).toDouble()
        s.camY=(-m[Matrix4.M13]/m[Matrix4.M11]*LiveOpticalScene.UNITS).toDouble()
        val dt=Gdx.graphics.deltaTime.coerceIn(1f/240,.1f)
        val speed=if(oldX.isFinite())hypot(s.camX-oldX,s.camY-oldY)/s.scale/dt else 0.0
        val zoom=if(oldScale.isFinite())ln(s.scale/oldScale) else 0.0
        s.parallax.followCamera(s.camX,s.camY,s.scale)
        // LiveOpticalDepth переносит начало Z к центру камеры, как для взвеси.
        s.focus=focus.update(dt,speed.toFloat(),zoom.toFloat(),(.5+settings.cellFocus*.045).toFloat(),settings.autoFocus).toDouble()
        oldX=s.camX;oldY=s.camY;oldScale=s.scale
        val motionDt=if(settings.animate&&previousTime.isFinite())((frame.time-previousTime+RenderFrame.TIME_WRAP)%RenderFrame.TIME_WRAP).coerceIn(0f,.1f) else 0f
        s.time+=motionDt
        previousTime=frame.time
        if(settings.cachePaused&&still.matches(frame,s)){
            s.update(dt,focus.hunting,0f)
            graph!!.renderPost(s,live,output?.framebufferHandle?:0)
            renderSprings(frame,s,live)
            return
        }

        val footprint=graph!!.eyepiece
        footprint.update(s)
        live.update(frame,s,footprint)

        s.liveRadius=live.averageRadius;s.liveCoverage=live.coverage;s.update(dt,focus.hunting,motionDt)
        graph!!.render(live=s,scene=live,outputFramebuffer=output?.framebufferHandle?:0)
        renderSprings(frame,s,live)
        still.remember(frame,s)
    }
    private fun renderSprings(frame:RenderFrame,s:OpticalState,live:LiveOpticalScene){
        val mesh=live.islands?:return
        if(!settings.debugSprings)return
        if(springs==null)springs=TissueSpringOverlay()

        springs!!.render(mesh,live.gpuData,s)

    }
    override fun dispose(){springs?.dispose();source?.dispose();graph?.dispose()}
}
