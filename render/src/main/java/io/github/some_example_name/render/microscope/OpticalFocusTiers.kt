package io.github.some_example_name.render.microscope

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL30.*
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.Disposable
import kotlin.math.abs

/** Persistent half-size scratch targets; no per-layer allocation or GPU readback. */
internal class OpticalFocusTiers(commands:List<OpticalPass>):Disposable {
    private val ids=commands.filter{it.stage in listOf("layer-maps","layer-psf")}.map{it.target}.distinct().filter{it>=0}
    private var maps=emptyMap<Int,OpticalTarget>()
    private var dye:OpticalTarget?=null
    private var compose:OpticalProgram?=null
    private val one=BufferUtils.newIntBuffer(1).put(intArrayOf(GL_COLOR_ATTACHMENT0)).flip()
    private val two=BufferUtils.newIntBuffer(2).put(intArrayOf(GL_COLOR_ATTACHMENT0,GL_COLOR_ATTACHMENT1)).flip()
    var nearest=2;private set
    var textureLimited=false;private set
    private var focusEnabled=false
    val width get()=dye!!.width
    val height get()=dye!!.height
    fun prepare(w:Int,h:Int,s:OpticalState,minimumRadius:Float):Boolean{
        focusEnabled=!s.settings.packedPsf
        val texelPixels=5.6*minimumRadius/TissueMaterialAtlas.SIZE/s.scale*w/s.width
        val threshold=s.settings.textureResolutionThreshold
        textureLimited=s.settings.textureResolution&&minimumRadius>0&&texelPixels>=threshold*(if(textureLimited).9 else 1.0)
        if(!focusEnabled&&!textureLimited)return false
        val lw=(w+1)/2;val lh=(h+1)/2
        if(dye?.width!=lw||dye?.height!=lh){
            for(t in maps.values)t.dispose();dye?.dispose()
            maps=ids.associateWith{OpticalTarget(lw,lh,true)};dye=OpticalTarget(lw,lh,true)
        }
        // Representative tissue depth; local relief still determines blur within each layer.
        val layers=1..3
        fun distance(i:Int)=abs(.5+.04*s.settings.reliefHeight*(.84-.5*TissueLayerGroups.depth(i,s.settings.tissueLayers))-s.focus)
        val active=layers.filter{TissueLayerGroups.owner(it,s.settings.tissueLayers)==it}
        val candidate=active.minBy{distance(it)}
        if(nearest !in active||distance(candidate)+.0005<distance(nearest))nearest=candidate
        dye!!.bind();Gdx.gl.glDisable(GL_SCISSOR_TEST);Gdx.gl.glColorMask(true,true,true,true)
        Gdx.gl.glClearColor(0f,0f,0f,0f);Gdx.gl.glClear(GL_COLOR_BUFFER_BIT)
        return true
    }
    fun low(slice:Int)=slice in 0..4&&((focusEnabled&&slice!=nearest)||(textureLimited&&slice in 1..3))
    fun has(index:Int)=maps.containsKey(index)
    fun texture(index:Int)=maps[index]?.texture?:0
    fun bind(index:Int,pigment:Boolean){
        maps.getValue(index).bind()
        Gdx.gl30.glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT1,GL_TEXTURE_2D,if(pigment)dye!!.texture else 0,0)
        Gdx.gl30.glDrawBuffers(if(pigment)2 else 1,if(pigment)two else one)
    }
    fun compose(base:OpticalProgram):OpticalProgram {
        return compose?:OpticalProgram(base.shader.vertexShaderSource,base.shader.fragmentShaderSource
            .replace("uniform sampler2D uPigment;","uniform sampler2D uPigment,uLowPigment;")
            .replace("texture(uPigment,uv).rgb","(texture(uPigment,uv).rgb+texture(uLowPigment,uv).rgb)"),true).also{compose=it}
    }
    fun bindDye(p:OpticalProgram){p.texture("uLowPigment",dye!!.texture,12)}
    override fun dispose(){for(t in maps.values)t.dispose();dye?.dispose();compose?.dispose()}
}
