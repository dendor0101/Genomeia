package io.github.some_example_name.render.microscope

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL30.*
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.Disposable

/** Одна RGBA16F-карта окраски: 1.68 МиБ при 1100x800 @ .5. Оба переключателя
 * сохраняют пять Z-срезов. Окраска суммируется при построении каждого среза,
 * с исходной аналитической расфокусировкой: второй цепочки PSF не нужно. */
internal class OpticalPassFusion(commands:List<OpticalPass>,private val sources:(Int,Boolean)->String):Disposable {
    private val raw=commands.first{it.stage=="layer-maps"&&it.blend!=null&&it.op=="draw"}
    private val accumulation=commands.first{it.stage=="opd-accumulation"&&it.op=="draw"}
    private val compose=commands.first{it.stage=="total-optics"&&it.op=="draw"}
    private val pigment=commands.first{it.stage=="muscle-pigment"&&it.op=="draw"}
    private var composeProgram:OpticalProgram?=null
    private var dye:OpticalTarget?=null
    val dyeTexture get()=dye?.texture?:0
    private val one=BufferUtils.newIntBuffer(1).put(intArrayOf(GL_COLOR_ATTACHMENT0)).flip()
    private val two=BufferUtils.newIntBuffer(2).put(intArrayOf(GL_COLOR_ATTACHMENT0,GL_COLOR_ATTACHMENT1)).flip()
    private val rawExtras=pigment.uniforms.filter { p->raw.uniforms.none{it.name==p.name} }
    fun configure(w:Int,h:Int){
        if(dye?.width!=w||dye?.height!=h){
            dye?.dispose();dye=OpticalTarget(w,h,true)
        }
    }
    fun skip(c:OpticalPass)=c.stage=="muscle-pigment"
    private fun pigmentLayer(c:OpticalPass)=c.program==raw.program&&c.slice in 1..3
    fun attach(c:OpticalPass,target:Int){
        if(target!=raw.target&&target!=accumulation.target)return
        val multiple=(c.op=="draw"&&pigmentLayer(c))||(c.op=="clear"&&target==accumulation.target)
        val gl=Gdx.gl30
        gl.glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT1,GL_TEXTURE_2D,if(multiple)dye!!.texture else 0,0)
        gl.glDrawBuffers(if(multiple)2 else 1,if(multiple)two else one)
    }
    fun program(c:OpticalPass):OpticalProgram? {
        if(c.program!=compose.program)return null
        return composeProgram?:OpticalProgram(sources(c.program,true),LiveOpticalDepth.source(OpticalFusionShaders.compose(sources(c.program,false))),true).also{composeProgram=it}
    }
    fun extras(c:OpticalPass)=if(c.program==raw.program)rawExtras else emptyList()
    fun textures(c:OpticalPass,p:OpticalProgram,field:Int){
        when(c.program){
            raw.program->p.texture("uTissueField",field,2)
            compose.program->p.texture("uPigment",dye!!.texture,4)
        }
    }
    override fun dispose(){composeProgram?.dispose();dye?.dispose()}
}
