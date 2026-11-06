package io.github.some_example_name.render.microscope

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL30.*
import com.badlogic.gdx.utils.IntMap
import com.badlogic.gdx.utils.Disposable
import kotlin.math.ceil

/** Один экранный тайл 16x16: GPU рисует консервативные прямоугольники клеток.
 * Маска сохраняет пустоты между телами; запаса хватает на две оси PSF и дисперсию.
 * На 1100x800 всего 13.5 КиБ. Нет readback, CPU-списка гранул или обхода мира. */
internal class OpticalWorkMask:Disposable {
    private val draw=GpuOpticalDraw()
    private val fill=OpticalProgram("vs_optical_work.vert","fs_optical_work.frag")
    private val variants=IntMap<OpticalProgram>()
    private var target:OpticalTarget?=null
    fun update(data:Int,count:Int,state:OpticalState){
        val w=ceil(state.width/16.0).toInt();val h=ceil(state.height/16.0).toInt()
        if(target?.width!=w||target?.height!=h){target?.dispose();target=OpticalTarget(w,h)
            Gdx.gl.glBindTexture(GL_TEXTURE_2D,target!!.texture);Gdx.gl.glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);Gdx.gl.glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST)}
        val gl=Gdx.gl;gl.glDisable(GL_SCISSOR_TEST);gl.glDisable(GL_BLEND);gl.glDisable(GL_DEPTH_TEST)
        target!!.clear();fill.use();fill.texture("uData",data,0)
        fill.v2("uCam",state.camX.toFloat(),state.camY.toFloat());fill.v2("uSpan",(state.width*state.scale).toFloat(),(state.height*state.scale).toFloat())
        fill.f("uPad",((40.0/state.settings.layerScale+32)*state.scale).toFloat())
        draw.cells(count);
    }
    fun program(original:OpticalProgram):OpticalProgram {
        val key=original.shader.handle
        return variants[key]?:OpticalProgram(original.shader.vertexShaderSource,
            original.shader.fragmentShaderSource.replace("void main(){","uniform highp sampler2D uWorkMask;\nvoid main(){if(texture(uWorkMask,gl_FragCoord.xy/uRes).r<.5){outColor=vec4(0.);return;}"),true).also{variants.put(key,it)}
    }
    fun bind(p:OpticalProgram){p.texture("uWorkMask",target!!.texture,10)}
    override fun dispose(){for(p in variants.values())p.dispose();target?.dispose();fill.dispose();draw.dispose()}
}
