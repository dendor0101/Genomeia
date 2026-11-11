package io.github.some_example_name.render.microscope

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL30.*
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.Disposable

/** Общий атлас только встретившихся типов. 3/2/1 внутренних + 2 оболочки, две RGBA16F с mipmaps:
 * 6.67/5.33/4 МиБ на тип (до 186.7/149.3/112 МиБ для всех 28).
 * Только выбранный вариант, без второго атласа и промежуточных карт запекания.
 * Формулы структуры исполняются при появлении нового типа; кадр читает готовые карты. */
internal class TissueMaterialAtlas(commands:List<OpticalPass>,sources:(Int,Boolean)->String):Disposable {
    private val raw=commands.first{it.stage=="layer-maps"&&it.op=="draw"&&it.blend!=null}
    private val resolve=commands.first{it.stage=="layer-maps"&&it.op=="draw"&&it.blend==null}
    private val original=sources(raw.program,false)
    private val materialSource:String=TissueIslandShaders.material(original,sources(resolve.program,false))
    val material:OpticalProgram=OpticalProgram(TissueIslandShaders.meshVertex,materialSource,true)
    // Кайма сетки плавнее старого SDF клеток. Возвращаем заметную оболочку,
    // не добавляя окантовок треугольникам и дополнительного экранного прохода.
    private val shellSource=LiveOpticalDepth.source(sources(resolve.program,false))
        .replace("*uContourWidth);","*uContourWidth*1.5);")
        .replace("(.075+.36*steep*focused)","(.16+.58*steep*focused)")
    val shell=OpticalProgram(sources(resolve.program,true),shellSource,true)
    private val contourDelegate=lazy {
        val prefix=shellSource.substringBefore("void main(){")
        OpticalProgram(sources(resolve.program,true),prefix+"""
uniform float uPhase;
void main(){
 vec2 uv=gl_FragCoord.xy/uRes,p=uCam+(uv-.5)*uSpan;
 vec3 specimen=tissueField(p);float field=mix(texture(uA,uv).r,specimen.x,uCohesion);
 // Only the thin boundary evaluates focus; no shell height or interior optics.
 float maxWidth=max(.0002,max(.0025,uScale*2.9)*uContourWidth*1.5);
 if(abs(field)>4.*sqrt(maxWidth*maxWidth+98.*uScale*uScale)||uContourWidth<=0.)discard;
 float depth=.5+.0018*(p.x-uCam.x)+.0012*(p.y-uCam.y)-uFocus;
 depth=mix(depth,TERRAIN_DEFOCUS,specimen.z);
 float focused=exp(-pow(depth*uAperture/2.6,2.));
 float width=max(.0002,max(.0025,uScale*(.65+2.25*focused))*uContourWidth*1.5);
 // Analytic broadening confined to the contour; no two full-screen blur passes.
 float sigma=clamp(.55+abs(depth)*uAperture,.55,7.)*uScale;
 float softWidth=sqrt(width*width+2.*sigma*sigma);
 float edge=exp(-pow(field/softWidth,2.))*width/softWidth*min(1.,uContourWidth);
 float opd=edge*.025,absorption=edge*(.16+.58*focused);
 outColor=vec4(opd,absorption,0.,opd*clamp(depth*38.+uPhase*.35,-1.5,1.5));
}
""".trimIndent(),true)
    }
    val contour get()=contourDelegate.value
    val body=OpticalProgram(TissueIslandShaders.meshVertex,TissueIslandShaders.geometryFragment,true)
    private val bake=OpticalProgram(TissueIslandShaders.templateVertex,TissueIslandShaders.templateFragment(original),true)
    private val tiles=IntArray(2);private val slots=IntArray(28)
    private val framebuffer=Gdx.gl.glGenFramebuffer();private val vao=IntArray(1)
    private val draws=BufferUtils.newIntBuffer(2).apply{put(GL_COLOR_ATTACHMENT0);put(GL_COLOR_ATTACHMENT1);flip()}
    private var readyMask=0
    private var readyLayers=3
    init {Gdx.gl30.glGenVertexArrays(1,vao,0)}
    fun prepare(tissue:Int,chip:Int,mask:Int,internalLayers:Int) {
        val needed=(mask or 1) or readyMask
        if(needed==readyMask&&readyLayers==internalLayers)return
        val gl=Gdx.gl30;val stride=internalLayers+2;val layers=Integer.bitCount(needed)*stride
        // Удалённый texture остаётся жив, пока прикреплён к FBO: отвязываем до новой аллокации.
        gl.glBindFramebuffer(GL_FRAMEBUFFER,framebuffer)
        for(i in tiles.indices)gl.glFramebufferTextureLayer(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0+i,0,0,0)
        gl.glDisable(GL_SCISSOR_TEST);gl.glDisable(GL_BLEND);gl.glDisable(GL_DEPTH_TEST);gl.glDisable(GL_CULL_FACE)
        for(i in tiles.indices){
            if(tiles[i]!=0)gl.glDeleteTexture(tiles[i]);tiles[i]=gl.glGenTexture()
            gl.glActiveTexture(GL_TEXTURE2);gl.glBindTexture(GL_TEXTURE_2D_ARRAY,tiles[i])
            gl.glTexImage3D(GL_TEXTURE_2D_ARRAY,0,GL_RGBA16F,SIZE,SIZE,layers,0,GL_RGBA,GL_HALF_FLOAT,null)
            gl.glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_MIN_FILTER,GL_LINEAR_MIPMAP_LINEAR)
            gl.glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_MAG_FILTER,GL_LINEAR)
            gl.glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_WRAP_S,GL_REPEAT)
            gl.glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_WRAP_T,GL_REPEAT)
        }
        gl.glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);gl.glDrawBuffers(2,draws)
        gl.glViewport(0,0,SIZE,SIZE);gl.glBindVertexArray(vao[0])
        bake.use();bake.texture("uTissue",tissue,0);bake.texture("uChip",chip,1);bake.f("uTileSize",SIZE.toFloat())
        var slot=0
        for(kind in slots.indices)if((needed and (1 shl kind))!=0){
            slots[kind]=slot;bake.f("uKind",kind+10f)
            var packed=0
            for(layer in 0..4){
                if(TissueLayerGroups.owner(layer,internalLayers)!=layer)continue
                for(i in tiles.indices)gl.glFramebufferTextureLayer(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0+i,tiles[i],0,slot*stride+packed)
                check(gl.glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE){"Tissue atlas framebuffer incomplete"}
                bake.f("uLayer",TissueLayerGroups.depth(layer,internalLayers))
                bake.f("uBakeFirst",TissueLayerGroups.first(layer,internalLayers).toFloat())
                bake.i("uBakeCount",TissueLayerGroups.size(layer,internalLayers))
                gl.glDrawArrays(GL_TRIANGLES,0,3);packed++
            }
            slot++
        }
        for(t in tiles){gl.glActiveTexture(GL_TEXTURE2);gl.glBindTexture(GL_TEXTURE_2D_ARRAY,t);gl.glGenerateMipmap(GL_TEXTURE_2D_ARRAY)}
        readyMask=needed;readyLayers=internalLayers;gl.glBindVertexArray(0)
    }
    fun bind(p:OpticalProgram){
        p.i("uTileStride",readyLayers+2)
        p.texture("uTemplates",tiles[0],3,GL_TEXTURE_2D_ARRAY);p.texture("uTemplateDye",tiles[1],4,GL_TEXTURE_2D_ARRAY)
        val loc=p.shader.fetchUniformLocation("uTileSlots[0]",false);if(loc>=0)Gdx.gl30.glUniform4iv(loc,7,slots,0)
    }
    override fun dispose(){if(contourDelegate.isInitialized())contour.dispose();shell.dispose();material.dispose();bake.dispose();body.dispose();for(t in tiles)if(t!=0)Gdx.gl.glDeleteTexture(t);Gdx.gl.glDeleteFramebuffer(framebuffer);Gdx.gl30.glDeleteVertexArrays(1,vao,0)}
    companion object {const val SIZE=256}
}
