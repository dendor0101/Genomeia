package io.github.some_example_name.render.microscope

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20.*
import com.badlogic.gdx.graphics.GL30.*
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.JsonReader
import kotlin.math.ceil

internal class OpticalPipeline:Disposable {
    private val directory=Gdx.files.internal("microscope/runtime")
    internal val document=JsonReader().parse(directory.child("pipeline.json"))
    var width=document.getInt("width");private set
    var height=document.getInt("height");private set
    private val textureSpecs=document.get("textures").toList()
    private val textures=IntArray(textureSpecs.size)
    private val framebuffers=IntArray(textureSpecs.size)
    private val depths=IntArray(textureSpecs.size)
    private val widths=IntArray(textureSpecs.size){textureSpecs[it].getInt("width")}
    private val heights=IntArray(textureSpecs.size){textureSpecs[it].getInt("height")}
    private val sizes=IntArray(textureSpecs.size)
    private val buffers=IntArray(document.get("buffers").size)
    private val programs=HashMap<Int,OpticalProgram>()
    private val commands=document.get("commands").map{OpticalPass(it)}
    private var fusion:OpticalPassFusion?=null
    private var bareEnvironment:OpticalEnvironment?=null
    internal val eyepiece=EyepieceFootprint()
    private var focusTiers:OpticalFocusTiers?=null
    private val packedPsf=arrayOfNulls<OpticalPackedPsf>(2)
    private val packedAperture=FloatArray(1)
    private val packedOutput=commands.first{it.stage=="layer-psf"&&it.uniforms.first{u->u.name=="uDirection"}.source[0]==0f}.target
    private var tissueIslands:TissueMaterialAtlas?=null
    private var workMask:OpticalWorkMask?=null
    private val vao=IntArray(1)
    private val geometryIndex=commands.first{it.stage=="geometry"&&it.op=="draw"}.target
    private var lastHalfGeometry=false
    private val textureTypes=IntArray(textureSpecs.size){type(textureSpecs[it].getInt("type"))}
    private val accumulationCommand=commands.first{it.stage=="opd-accumulation"&&it.op=="draw"}
    // A standalone contour command: neither shell belongs to this render graph.
    private val contourCommand by lazy {
        val node=document.get("commands").first{it.getString("stage","")=="layer-maps"&&it.getString("op")=="draw"&&it.getInt("slice",-1)==0&&it.get("blend").isNull}
        val copy=JsonReader().parse(node.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json))
        copy.remove("slice");copy.addChild("slice",com.badlogic.gdx.utils.JsonValue(-1L))
        OpticalPass(copy)
    }
    private val contourCommands by lazy {
        commands.mapNotNull { cmd ->
            if(cmd.slice==0&&cmd.stage=="layer-maps"&&cmd.op=="draw"&&cmd.blend==null)contourCommand
            else if(cmd.slice==0||cmd.slice==4)null else cmd
        }
    }
    private var first=true
    private var lastLayerScale=document.get("state").getFloat("layerScale")
    init {
        val gl=Gdx.gl30;gl.glGenVertexArrays(1,vao,0)
        val roles=document.get("resources")
        if(roles!=null)for((i,name)in arrayOf("full","half","quarter","volume").withIndex())for(t in roles.get(name))sizes[t.asInt()]=i+1
        // Старые промежуточные карты взвеси не занимают полноэкранную память живой сцены.
        val unusedLiveTargets=commands.filter{it.op=="draw" && it.stage in
            setOf("atlas","environment","material","halo","environment-optics","environment-dof")
        }.map{it.target}.toSet()+roles.get("full").getInt(3)+roles.get("full").getInt(4)
        for(i in textures.indices){
            if(i in unusedLiveTargets){widths[i]=1;heights[i]=1;sizes[i]=0}
            val spec=textureSpecs[i];textures[i]=gl.glGenTexture();gl.glBindTexture(GL_TEXTURE_2D,textures[i])
            // В живой игре эти три карты принадлежат LiveOpticalScene.
            val supplied= (i==roles.getInt("data") || i==roles.getInt("field") || i==roles.getInt("tint"))
            if(supplied){widths[i]=1;heights[i]=1}
            val bytes=if(supplied)null else spec.getString("data",null)?.let{read(it)}
            val data=bytes?.let{BufferUtils.newByteBuffer(it.size).put(it).also{b->b.flip()}}
            val type=type(spec.getInt("type"));gl.glPixelStorei(GL_UNPACK_ALIGNMENT,1)
            gl.glTexImage2D(GL_TEXTURE_2D,0,format(type),widths[i],heights[i],0,GL_RGBA,type,data)
            for(p in spec.get("params"))gl.glTexParameteri(GL_TEXTURE_2D,p.name.toInt(),p.asInt())
        }
        for((i,spec)in document.get("buffers").withIndex()){
            val data=spec.get("vertices").asFloatArray();val b=BufferUtils.newFloatBuffer(data.size);b.put(data).flip()
            buffers[i]=gl.glGenBuffer();gl.glBindBuffer(GL_ARRAY_BUFFER,buffers[i]);gl.glBufferData(GL_ARRAY_BUFFER,data.size*4,b,GL_STATIC_DRAW)
        }
    }
    private fun read(name:String)=OpticalAssetData.read(directory,name)
    private fun type(t:Int)=if(t==36193)GL_HALF_FLOAT else t
    private fun format(t:Int)=if(t==GL_FLOAT)GL_RGBA32F else if(t==GL_HALF_FLOAT)GL_RGBA16F else GL_RGBA8
    private fun shaderSource(index:Int,vertex:Boolean):String {
        val spec=document.get("programs").get(index);val kind=if(vertex)"vertex" else "fragment"
        return Gdx.files.internal(spec.getString(kind+"File")).readString("UTF-8")
    }
    private var outputFramebuffer = 0
    private fun bind(index:Int){
        val gl=Gdx.gl30
        if(index<0){gl.glBindFramebuffer(GL_FRAMEBUFFER,outputFramebuffer);return}
        if(framebuffers[index]==0){
            framebuffers[index]=gl.glGenFramebuffer();gl.glBindFramebuffer(GL_FRAMEBUFFER,framebuffers[index])
            gl.glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,textures[index],0)
            if(commands.any{it.target==index&&it.depth}){
                depths[index]=gl.glGenRenderbuffer();gl.glBindRenderbuffer(GL_RENDERBUFFER,depths[index])
                gl.glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH_COMPONENT16,widths[index],heights[index])
                gl.glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_RENDERBUFFER,depths[index])
            }
            check(gl.glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE){"Microscope FBO $index incomplete (requires RGBA32F and RGBA16F render targets)"}
        }else gl.glBindFramebuffer(GL_FRAMEBUFFER,framebuffers[index])
    }
    /** Меняем только затронутые текстуры. Дополнительной полной копии для A/B нет:
     * геометрия 50% экономит 3/4 своего буфера, материал 16F — половину своего. */
    private fun resize(w:Int,h:Int,scale:Float,halfGeometry:Boolean):Boolean {
        if(w==width&&h==height&&scale==lastLayerScale&&halfGeometry==lastHalfGeometry)return false
        width=w;height=h;lastLayerScale=scale;lastHalfGeometry=halfGeometry
        val gl=Gdx.gl30
        for(i in textures.indices)if(sizes[i]!=0){
            val ratio=if(i==geometryIndex&&halfGeometry).5f else when(sizes[i]){1->1f;2->.5f;3->.25f;else->scale}
            val tw=ceil(w*ratio).toInt().coerceAtLeast(1);val th=ceil(h*ratio).toInt().coerceAtLeast(1)
            val t=type(textureSpecs[i].getInt("type"))
            if(widths[i]==tw&&heights[i]==th&&textureTypes[i]==t)continue
            widths[i]=tw;heights[i]=th;textureTypes[i]=t
            gl.glBindTexture(GL_TEXTURE_2D,textures[i]);gl.glTexImage2D(GL_TEXTURE_2D,0,format(t),tw,th,0,GL_RGBA,t,null)
            if(depths[i]!=0){gl.glBindRenderbuffer(GL_RENDERBUFFER,depths[i]);gl.glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH_COMPONENT16,tw,th)}
        }
        return true
    }
    fun render(live:OpticalState,scene:LiveOpticalScene,outputFramebuffer:Int=0){
        this.outputFramebuffer=outputFramebuffer
        val islands=scene.islands
        run {
            if(bareEnvironment==null)bareEnvironment=OpticalEnvironment(commands,::shaderSource)
            bareEnvironment!!.prepare(live,textures[bareEnvironment!!.spriteSource],eyepiece)
            if(!islands.hasSurface){
                // Пустая ткань даёт константу: не очищаем и не размываем пять
                // полноэкранных карт. Размеры графа сохраняем: при возврате тела
                // resize обязан увидеть изменения окна, сделанные в пустом кадре.
                renderPost(live,scene,outputFramebuffer)
                return
            }
        }
        if(tissueIslands==null)tissueIslands=TissueMaterialAtlas(commands,::shaderSource)
        resize(live.width,live.height,live.settings.layerScale,live.settings.halfGeometry)

        run {
            if(fusion==null)fusion=OpticalPassFusion(commands,::shaderSource)
            val volume=document.get("resources").get("volume").getInt(0)
            fusion!!.configure(widths[volume],heights[volume])
        }
        val gl=Gdx.gl30;gl.glDisable(GL_CULL_FACE);gl.glDisable(GL_BLEND);gl.glDisable(GL_SCISSOR_TEST);gl.glDisable(GL_DEPTH_TEST)
        // Плотному кадру маска не окупается; общий scissor всё равно применяется.
        val bounded=live.settings.boundedOptics&&live.liveCoverage<.12
        if(bounded){
            if(workMask==null)workMask=OpticalWorkMask()
            workMask!!.update(scene.texture(document.get("resources").getInt("data")),scene.count,live)
        }
        gl.glDepthFunc(GL_LESS);gl.glDepthMask(true);gl.glColorMask(true,true,true,true);gl.glEnable(GL_DITHER)
        gl.glBindVertexArray(vao[0])
        val dirt=document.get("resources")?.get("full")?.getInt(4)?:-2
        val tiered=if(!live.settings.packedPsf||live.settings.textureResolution){
            if(focusTiers==null)focusTiers=OpticalFocusTiers(commands)
            val v=document.get("resources").get("volume").getInt(0)
            focusTiers!!.prepare(widths[v],heights[v],live,islands.minimumMaterialRadius)
        }else false
        // At extreme zoom half-resolution interiors already save more than compaction.
        val packed=live.settings.packedPsf&&!(tiered&&focusTiers!!.textureLimited)
        if(packed)for(pass in packedPsf)pass?.beginFrame()
        val tissueLayers=live.settings.tissueLayers
        for(cmd in contourCommands){
            if(TissueLayerGroups.owner(cmd.slice,tissueLayers)!=cmd.slice)continue
            val contourDraw=cmd===contourCommand
            if(bareEnvironment!!.skip(cmd,dirt))continue
            val instanced=cmd.attrs.any { it[6]!=0 }
            if(!first&&cmd.op=="mipmap")continue
            if(live.settings.layer>=0&&cmd.slice>=0&&cmd.slice!=TissueLayerGroups.owner(live.settings.layer.coerceIn(1,3),tissueLayers))continue
            if(fusion?.skip(cmd)==true)continue
            if(cmd.op=="mipmap"){
                gl.glActiveTexture(GL_TEXTURE0);gl.glBindTexture(GL_TEXTURE_2D,textures[cmd.texture]);gl.glGenerateMipmap(GL_TEXTURE_2D);continue
            }
            val lowLayer=tiered&&focusTiers!!.low(cmd.slice)
            val lowTarget=lowLayer&&focusTiers!!.has(cmd.target)
            val target=if(contourDraw)accumulationCommand.target else cmd.target
            val sharedRaw=cmd.op=="draw"&&cmd.stage=="layer-maps"&&instanced
            if(sharedRaw){
                if(tissueIslands==null)tissueIslands=TissueMaterialAtlas(commands,::shaderSource)
                tissueIslands!!.prepare(textures[cmd.units.first{it[0]==0}[1]],textures[cmd.units.first{it[0]==1}[1]],islands.materialMask,tissueLayers)
            }
            if(lowTarget){focusTiers!!.bind(target,cmd.op=="draw"&&cmd.stage=="layer-maps"&&instanced&&cmd.slice in 1..3)}else{bind(target);fusion?.attach(cmd,target)}
            val tw=if(lowTarget)focusTiers!!.width else if(target<0)width else widths[target];val th=if(lowTarget)focusTiers!!.height else if(target<0)height else heights[target]
            // Контур рисуется отдельно; атлас PSF обрабатывает только внутренние слои.
            if(packed&&cmd.stage=="layer-psf"&&cmd.slice in 1..3){
                val direction=cmd.uniforms.first{it.name=="uDirection"}.source
                if(direction[0]!=0f){
                    val slot=if(lowLayer)1 else 0
                    val pass=packedPsf[slot]?:OpticalPackedPsf().also{packedPsf[slot]=it}
                    val input=cmd.units.first{it[0]==0}[1]
                    val tex=if(lowLayer)focusTiers!!.texture(input) else textures[input]
                    live.uniform("uAperture",packedAperture,cmd.stage,tw,th)
                    pass.render(tex,tw,th,packedAperture[0],tw.toFloat()/width,live.settings.tissueBlur,eyepiece,{if(lowLayer)focusTiers!!.bind(packedOutput,false) else bind(packedOutput)}){p->
                        p.v2("uCam",live.camX.toFloat(),live.camY.toFloat());p.v2("uSpan",(live.width*live.scale).toFloat(),(live.height*live.scale).toFloat())
                        p.texture("uLiveData",scene.gpuData,6);islands.draw(p,6)
                    }
                }
                continue
            }
            val crop= eyepiece.enabled && eyepiece.cropLayers &&
                cmd.op=="draw" && cmd.stage!="post"
            // A single scissor is cheaper than tessellating a small target or a sparse ROI.
            val fan = crop && !instanced && tw.toLong()*th >= 800_000 && !bounded
            if(crop && !fan){
                val rect=if(cmd.scissor!=null && live.settings.boundedOptics && scene.workRect(live,tw,th,live.rect)) live.rect else null
                eyepiece.scissor(tw,th,rect)
            }else if(cmd.scissor==null)gl.glDisable(GL_SCISSOR_TEST) else {
                gl.glEnable(GL_SCISSOR_TEST)
                if(live.settings.boundedOptics&&cmd.op!="clear"&&scene.workRect(live,tw,th,live.rect)){val s=live.rect;gl.glScissor(s[0],s[1],s[2],s[3])}
                else gl.glScissor(0,0,tw,th)
            }
            if(cmd.op=="clear"){
                val c=cmd.color;gl.glClearColor(c[0],c[1],c[2],c[3]);gl.glClearDepthf(cmd.clearDepth);gl.glClear(cmd.mask);continue
            }
            gl.glViewport(0,0,tw,th)
            if(cmd.depth)gl.glEnable(GL_DEPTH_TEST) else gl.glDisable(GL_DEPTH_TEST)
            val b=cmd.blend;if(b==null)gl.glDisable(GL_BLEND) else {gl.glEnable(GL_BLEND);gl.glBlendEquation(GL_FUNC_ADD);gl.glBlendFunc(b[0],b[1])}
            if(contourDraw){gl.glEnable(GL_BLEND);gl.glBlendEquation(GL_FUNC_ADD);gl.glBlendFunc(GL_ONE,GL_ONE)}
            if(cmd.stage=="geometry"){gl.glDisable(GL_BLEND);gl.glDisable(GL_DEPTH_TEST)}
            val variant=(if(contourDraw)tissueIslands!!.contour else null)?:(if(cmd.stage=="layer-maps"&&cmd.blend==null)tissueIslands!!.shell else null)?:(bareEnvironment!!.program(cmd))?:fusion?.program(cmd)
            val base=if(sharedRaw)tissueIslands!!.material else if(cmd.stage=="geometry")tissueIslands!!.body else variant?:programs.getOrPut(cmd.program){OpticalProgram(shaderSource(cmd.program,true),LiveOpticalDepth.source(shaderSource(cmd.program,false)),true)}
            val maskProgram=if(bounded&&!instanced&&cmd.scissor!=null)workMask!!.program(base) else null
            val program=if(tiered&&cmd.stage=="total-optics")focusTiers!!.compose(base) else maskProgram?:base
            program.use()
            for(u in cmd.uniforms){
                val l=program.shader.fetchUniformLocation(u.name,false)
                uniform(u,l,live,scene,cmd.stage,tw,th)
            }
            if(cmd.slice in 1..3)program.f("uLayer",TissueLayerGroups.depth(cmd.slice,tissueLayers))
            if(sharedRaw){
                program.i("uTileLayer",if(cmd.slice==0)0 else if(cmd.slice==4)tissueLayers+1 else if(tissueLayers==3)cmd.slice else if(tissueLayers==2&&cmd.slice==3)2 else 1)
            }
            if(contourDraw)for(u in accumulationCommand.uniforms)if(u.name=="uPhase")uniform(u,program.shader.fetchUniformLocation(u.name,false),live,scene,accumulationCommand.stage,tw,th)
            if(variant!=null||sharedRaw)for(u in fusion?.extras(cmd)?:emptyList())uniform(u,program.shader.fetchUniformLocation(u.name,false),live,scene,cmd.stage,tw,th)
            for(unit in cmd.units){
                val index=unit[1]
                val replacement=if(lowLayer&&focusTiers!!.has(index))focusTiers!!.texture(index) else if(index==dirt)bareEnvironment!!.dirtTexture else if(index==bareEnvironment!!.background)bareEnvironment!!.texture else scene.texture(index)
                gl.glActiveTexture(GL_TEXTURE0+unit[0]);gl.glBindTexture(GL_TEXTURE_2D,if(replacement!=0)replacement else if(index<0)0 else textures[index])
            }
            if(variant!=null||sharedRaw){
                val field=document.get("resources").getInt("field")
                val replacement=scene.texture(field)
                fusion?.textures(cmd,program,if(replacement!=0)replacement else textures[field])
            }
            if(sharedRaw)tissueIslands!!.bind(program)
            if(tiered&&cmd.stage=="total-optics")focusTiers!!.bindDye(program)

            if((sharedRaw||cmd.stage=="geometry")){
                program.v2("uCam",live.camX.toFloat(),live.camY.toFloat())
                program.v2("uSpan",(live.width*live.scale).toFloat(),(live.height*live.scale).toFloat())
            }
            if(maskProgram!=null)workMask!!.bind(program)
            program.texture("uLiveData",scene.gpuData,6)
            gl.glBindVertexArray(vao[0])
            for(i in 0..3){gl.glDisableVertexAttribArray(i);gl.glVertexAttribDivisor(i,0)}
            for(a in cmd.attrs){
                if(a[6]!=0)continue
                gl.glBindBuffer(GL_ARRAY_BUFFER,if(fan)eyepiece.buffer  else buffers[a[1]]);gl.glEnableVertexAttribArray(a[0]);gl.glVertexAttribPointer(a[0],a[2],a[3],false,a[4],a[5]);gl.glVertexAttribDivisor(a[0],a[6])
            }

            if(sharedRaw||cmd.stage=="geometry"){islands.draw(program)} else if(fan){eyepiece.draw()} else gl.glDrawArraysInstanced(cmd.mode,cmd.first,cmd.count,cmd.instances)

        }
        first=false;gl.glDisable(GL_SCISSOR_TEST);gl.glDisable(GL_DEPTH_TEST);gl.glDisable(GL_BLEND);gl.glBindVertexArray(0);gl.glActiveTexture(GL_TEXTURE0)
    }
    /** Все промежуточные текстуры уже готовы. Остаётся камера и вывод, без новой FBO. */
    internal fun renderPost(live:OpticalState,scene:LiveOpticalScene,output:Int){
        outputFramebuffer=output
        val gl=Gdx.gl30;val cmd=commands.last{it.stage=="post"&&it.op=="draw"}
        val islands=scene.islands
        val empty=!islands.hasSurface
        val emptyTexture=if(empty)bareEnvironment!!.emptyTexture(live) else 0
        val p=bareEnvironment!!.post
        bind(-1);gl.glViewport(0,0,live.width,live.height)
        gl.glDisable(GL_SCISSOR_TEST);gl.glDisable(GL_DEPTH_TEST);gl.glDisable(GL_BLEND);gl.glDisable(GL_CULL_FACE)
        gl.glColorMask(true,true,true,true);gl.glEnable(GL_DITHER);gl.glBindVertexArray(vao[0]);p.use()
        for(u in cmd.uniforms)uniform(u,p.shader.fetchUniformLocation(u.name,false),live,scene,cmd.stage,live.width,live.height)
        for(unit in cmd.units){gl.glActiveTexture(GL_TEXTURE0+unit[0]);gl.glBindTexture(GL_TEXTURE_2D,textures[unit[1]])}
        if(live.settings.dirt)p.texture("uDirtTex",bareEnvironment!!.dirtTexture,cmd.uniforms.first{it.name=="uDirtTex"}.source[0].toInt())
        if(empty)p.texture("uCol",emptyTexture,0)
        for(i in 0..3){gl.glDisableVertexAttribArray(i);gl.glVertexAttribDivisor(i,0)}
        for(a in cmd.attrs){gl.glBindBuffer(GL_ARRAY_BUFFER,buffers[a[1]]);gl.glEnableVertexAttribArray(a[0]);gl.glVertexAttribPointer(a[0],a[2],a[3],false,a[4],a[5]);gl.glVertexAttribDivisor(a[0],a[6])}
        gl.glDrawArraysInstanced(cmd.mode,cmd.first,cmd.count,cmd.instances);
        gl.glBindVertexArray(0);gl.glActiveTexture(GL_TEXTURE0)
    }
    private fun uniform(u:OpticalPass.Uniform,l:Int,live:OpticalState,scene:LiveOpticalScene,stage:String,w:Int,h:Int){
        if(l<0)return
        u.source.copyInto(u.value);val a=u.value;live.uniform(u.name,a,stage,w,h);scene.uniform(u.name,a)
        if((u.name=="uTintStrength"||u.name=="uColorFilter"))a[0]=0f
        if((!live.settings.packedPsf||live.settings.textureResolution)&&u.name=="uPixelRatio"&&(stage=="layer-maps"||stage=="layer-psf"))a[0]=w.toFloat()/width
        val gl=Gdx.gl30
        when(u.type){
            GL_SAMPLER_2D,GL_INT,GL_BOOL->gl.glUniform1i(l,a[0].toInt())
            GL_FLOAT->gl.glUniform1fv(l,a.size,a,0)
            GL_FLOAT_VEC2->gl.glUniform2fv(l,a.size/2,a,0)
            GL_FLOAT_VEC3->gl.glUniform3fv(l,a.size/3,a,0)
            GL_FLOAT_VEC4->gl.glUniform4fv(l,a.size/4,a,0)
            else->error("Unsupported optical uniform ${u.type}")
        }
    }
    override fun dispose(){eyepiece.dispose();for(p in packedPsf)p?.dispose();focusTiers?.dispose();bareEnvironment?.dispose();tissueIslands?.dispose();workMask?.dispose();fusion?.dispose();for(p in programs.values)p.dispose();for(t in textures)Gdx.gl.glDeleteTexture(t);for(f in framebuffers)if(f!=0)Gdx.gl.glDeleteFramebuffer(f);for(d in depths)if(d!=0)Gdx.gl.glDeleteRenderbuffer(d);for(b in buffers)Gdx.gl.glDeleteBuffer(b);Gdx.gl30.glDeleteVertexArrays(1,vao,0)}
}
