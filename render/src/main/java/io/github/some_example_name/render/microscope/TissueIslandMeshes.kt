package io.github.some_example_name.render.microscope

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL30.*
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.IntMap
import io.github.some_example_name.render.pack.OpticalLinks
import io.github.some_example_name.render.pack.CellInstanceBuffer as Cells
import java.nio.ByteBuffer

/** Кэш реальных треугольников. Живые позиции читаются на GPU, UV неизменны.
 * 16 B/вершину + 16 B/треугольник (~480 КиБ/10k клеток), без загрузки сетки за кадр.
 * UV сохраняются и вне экрана до удаления тела/смены мира. Сильные складки вне эксперимента. */
internal class TissueIslandMeshes(private val materials:IntArray):Disposable {
    private class Body(val signature:Long,val mesh:TissueTriangles,val coordinates:TissueRestCoordinates,var rest:Int,val triangles:Int,val rimNodes:Int,val renderTriangles:Int,val edges:IntArray,val materials:Int,val particles:Boolean,val canSmooth:Boolean,var smoothUvCount:Int):Disposable {
        var inEyepiece=true;var springs=0;var radius=0f
        /** Террейн (OpticalLinks.TERRAIN_GROUP): ткань всегда над плоскостью фокуса, см. uLift в шейдерах. */
        var lifted=false
        var output=0;var stamp=0
        override fun dispose(){Gdx.gl.glDeleteTexture(rest);Gdx.gl.glDeleteTexture(triangles);Gdx.gl.glDeleteTexture(rimNodes);if(springs!=0)Gdx.gl.glDeleteTexture(springs)}
    }
    private val bodies=IntMap<Body>()
    private val visible=ArrayList<Body>()
    // Rest-space scale, constant metadata per visible body (no cell/pixel traversal).
    val minimumMaterialRadius get()=visible.minOfOrNull{it.coordinates.radius}?:0f
    private var local=IntArray(0)
    private var owner=IntArray(0)
    private var epoch:Any?=null
    private var stamp=0
    private var mappingSerial=0
    private var smoothContour=0f
    var materialMask=0;private set
    var builds=0;private set
    var smoothUvBuilds=0;private set
    var triangleCount=0;private set
    val hasSurface get()=visible.any{it.renderTriangles>0}
    var rejectedEdges=0;private set
    private val vao=IntArray(1)
    private val fieldProgram=OpticalProgram(TissueIslandShaders.meshVertex,TissueIslandShaders.fieldFragment,true)
    init {Gdx.gl30.glGenVertexArrays(1,vao,0)}
    fun begin(world:Any?,smoothing:Float){
        smoothContour=smoothing
        if(world!==epoch){for(b in bodies.values())b.dispose();bodies.clear();epoch=world}
        stamp++;visible.clear();triangleCount=0;rejectedEdges=0;materialMask=0
    }
    fun retain(id:Int){bodies[id]?.stamp=stamp}
    fun body(cells:ByteBuffer,next:IntArray,first:Int,count:Int,id:Int,output:Int,signature:Long,links:OpticalLinks?,maxRadius:Float,inEyepiece:Boolean){

        val n=cells.remaining()/Cells.STRUCT_SIZE
        if(local.size<n){local=IntArray(n);owner=IntArray(n)}
        val mapping=++mappingSerial
        var at=first;var k=0
        while(at>=0){local[at]=k++;owner[at]=mapping;at=next[at]}
        val particles=id==Int.MIN_VALUE
        var hash=if(particles)count.toLong() else signature;at=first
        while(at>=0&&!particles){
            hash=hash*1000003L+(links?.vertexIds?.getOrNull(at)?:at.toLong()+1)
            for(c in 0..2)hash=hash*1000003L+(links?.materialRest?.getOrNull(at*3+c)?:0f).toRawBits()
            for(e in (links?.offsets?.getOrNull(at)?:0) until (links?.offsets?.getOrNull(at+1)?:0)){val b=links!!.neighbors[e]
                if(b in 0 until n&&owner[b]==mapping)hash=hash*1000003L+local[at]*65537L+local[b]}
            at=next[at]
        }
        var b=bodies[id]
        if(b==null||b.signature!=hash||b.mesh.count!=count){
            val poses=FloatArray(count*4);val ids=LongArray(count);val reference=FloatArray(count*3);val edges=ArrayList<Int>();var kinds=0;at=first;k=0
            while(at>=0){val o=cells.position()+at*Cells.STRUCT_SIZE
                ids[k]=(links?.vertexIds?.getOrNull(at)?:at.toLong()+1)
                reference[k*3]=(links?.materialRest?.getOrNull(at*3)?:0f);reference[k*3+1]=(links?.materialRest?.getOrNull(at*3+1)?:0f)
                reference[k*3+2]=(links?.materialRest?.getOrNull(at*3+2)?:0f)*LiveOpticalScene.UNITS
                val kind=if((cells.getInt(o+16) ushr 24 and Cells.FLAG_NOT_CELL)!=0)27 else materials.getOrElse(cells.getInt(o+16) and 255){27}.coerceIn(0,27);kinds=kinds or (1 shl kind)
                poses[k*4]=cells.getFloat(o)*LiveOpticalScene.UNITS;poses[k*4+1]=cells.getFloat(o+4)*LiveOpticalScene.UNITS
                poses[k*4+3]=(Cells.RADIUS_MIN+(cells.getInt(o+12) ushr 16 and 255)/255f*Cells.RADIUS_SPAN)*LiveOpticalScene.UNITS
                if(particles){poses[k*4]=0f;poses[k*4+1]=0f;poses[k*4+3]=maxRadius;ids[k]=k.toLong()+1}
                for(e in (links?.offsets?.getOrNull(at)?:0) until (links?.offsets?.getOrNull(at+1)?:0)){val j=links!!.neighbors[e]
                    if(j in 0 until n&&owner[j]==mapping&&k<local[j]){edges.add(k);edges.add(local[j])}}
                k++;at=next[at]
            }
            val edgeArray=edges.toIntArray()
            val mesh=TissueTriangles(poses,edgeArray)
            val coordinates=TissueRestCoordinates(mesh,ids,edgeArray,b?.coordinates,reference)
            val rim=TissueRim(mesh,coordinates.transforms,edgeArray)
            val canSmooth=rim.edgeCount>0&&count>4
            val prepareUvs=canSmooth&&smoothContour>0f
            val smoothUvs=if(prepareUvs){smoothUvBuilds++;TissueContourUv.pack(rim.rest,rim.nodes,mesh.count)} else rim.rest
            b?.dispose()
            b=Body(hash,mesh,coordinates,texture(smoothUvs),texture(rim.triangles),texture(rim.nodes),rim.triangles.size/4,edgeArray,kinds,particles,canSmooth,if(prepareUvs)rim.rest.size/4 else 0)
            bodies.put(id,b);builds++
        }
        if(smoothContour>0f&&b.canSmooth&&b.smoothUvCount==0){
            // Только первое включение для этой версии тела. Не храним лишнюю
            // CPU-копию каймы при 0%; воспроизводим её из уже готовой топологии.
            val rim=TissueRim(b.mesh,b.coordinates.transforms,b.edges)
            val packed=TissueContourUv.pack(rim.rest,rim.nodes,b.mesh.count)
            val replacement=texture(packed)
            Gdx.gl.glDeleteTexture(b.rest);b.rest=replacement;b.smoothUvCount=rim.rest.size/4;smoothUvBuilds++
        }
        b.inEyepiece=inEyepiece
        b.radius=maxRadius
        b.lifted=id==OpticalLinks.TERRAIN_GROUP
        b.output=output;b.stamp=stamp;visible.add(b);materialMask=materialMask or b.materials;triangleCount+=b.mesh.indices.size/3;rejectedEdges+=b.mesh.rejectedEdges
    }
    private fun texture(values:FloatArray):Int{
        val gl=Gdx.gl30;val width=minOf(256,maxOf(1,values.size/4));val rows=maxOf(1,(values.size/4+width-1)/width);val data=BufferUtils.newFloatBuffer(width*rows*4)
        data.put(values);data.position(0);val t=gl.glGenTexture();gl.glActiveTexture(GL_TEXTURE0);gl.glBindTexture(GL_TEXTURE_2D,t)
        gl.glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,width,rows,0,GL_RGBA,GL_FLOAT,data)
        gl.glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);gl.glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST)
        gl.glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);gl.glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);return t
    }
    fun end(){val it=bodies.entries();while(it.hasNext()){val e=it.next();if(e.value.stamp!=stamp){e.value.dispose();it.remove()}}}
    fun field(target:OpticalTarget,input:GpuOpticalInput,state:OpticalState,footprint:EyepieceFootprint){
        smoothContour=state.settings.smoothContour
        val gl=Gdx.gl30;gl.glDisable(GL_SCISSOR_TEST);gl.glDisable(GL_BLEND);gl.glDisable(GL_DEPTH_TEST)
        target.bind();fieldProgram.use();fieldProgram.texture("uLiveData",input.table.texture,6)
        fieldProgram.v2("uCam",state.camX.toFloat(),state.camY.toFloat());fieldProgram.v2("uSpan",(state.width*state.scale).toFloat(),(state.height*state.scale).toFloat())
        val crop=footprint.enabled && footprint.cropLayers
        if(crop)footprint.scissor(target.width,target.height)
        draw(fieldProgram)
        if(crop)gl.glDisable(GL_SCISSOR_TEST)
    }
    fun draw(program:OpticalProgram,vertices:Int=3){
        val gl=Gdx.gl30;gl.glBindVertexArray(vao[0])
        for(b in visible){
            if(!b.inEyepiece)continue
            // У зародыша нет внутренней области: сглаживание всего периметра
            // стягивало его почти в одну точку. Включаем постепенно с 5 до 12 клеток.
            program.f("uSmoothContour",if(b.canSmooth)smoothContour*((b.mesh.count-4)/8f).coerceIn(0f,1f) else 0f)
            program.i("uParticleBatch",if(b.particles)1 else 0)
            program.f("uLift",if(b.lifted)1f else 0f)
            program.i("uSmoothUvCount",b.smoothUvCount)
            program.i("uBodyOffset",b.output);program.texture("uRest",b.rest,8);program.texture("uTriangles",b.triangles,9)
            program.f("uMaterialRadius",b.coordinates.radius)
            program.i("uCoreCount",b.mesh.count);program.f("uRimRadius",b.radius);program.texture("uRimNodes",b.rimNodes,11)
            gl.glDrawArraysInstanced(GL_TRIANGLES,0,vertices,b.renderTriangles)
        }
        gl.glBindVertexArray(0)
    }
    /** Исходные физические связи, включая отброшенные при построении поверхности.
     * CPU-кэш: 8 B/связь (~240 КиБ/30k), потоковый обход только при первой загрузке.
     * GPU-кэш создаётся по требованию: 16 B/связь; позиции берём из живой таблицы. */
    fun drawSprings(program:OpticalProgram){
        val gl=Gdx.gl30;gl.glBindVertexArray(vao[0])
        for(b in visible){
            if(b.edges.isEmpty())continue
            if(b.springs==0){
                val data=FloatArray(b.edges.size*2)
                for(i in b.edges.indices step 2){data[i*2]=b.edges[i].toFloat();data[i*2+1]=b.edges[i+1].toFloat()}
                b.springs=texture(data)
            }
            program.i("uBodyOffset",b.output);program.texture("uSprings",b.springs,10)
            gl.glDrawArraysInstanced(GL_LINES,0,2,b.edges.size/2)
        }
        gl.glBindVertexArray(0)
    }
    override fun dispose(){for(b in bodies.values())b.dispose();bodies.clear();fieldProgram.dispose();Gdx.gl30.glDeleteVertexArrays(1,vao,0)}
}
