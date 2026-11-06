package io.github.some_example_name.render.microscope

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL30.*
import com.badlogic.gdx.utils.IntMap
import io.github.some_example_name.render.RenderFrame
import io.github.some_example_name.render.pack.CellInstanceBuffer as Cells
import kotlin.math.*

/** Полные диапазоны тел сначала отсекаются по границам снимка. CPU группирует только
 * оставшиеся индексы и уточняет AABB для размера GPU-поля; без метаданных — полный обход.
 * Пиксели, EDT/JFA, высоты, сглаживание, углы и интерполяция данных HTML — на GPU.
 * В полигональном эксперименте дополнительно кэшируются реальные грани графа;
 * построение сетки выполняется только при смене состава/связей, а не при движении.
 * next + singles: 8 байт/вход (78 КиБ на 10k); потоковые обращения по арене тела. */
internal class LiveOpticalScene(materials:IntArray):com.badlogic.gdx.utils.Disposable {
    val islands=TissueIslandMeshes(materials)
    private val draw=GpuOpticalDraw()
    private val input=GpuOpticalInput(materials,draw)
    var count=0;private set
    val gpuData get()=input.table.texture
    private var next=IntArray(0)
    private val bodies=IntMap<Body>()
    private val visible=ArrayList<Body>()
    private var field:OpticalTarget?=null
    private val bounds=FloatArray(4)
    private val workBounds=OpticalWorkBounds()
    fun workRect(state:OpticalState,w:Int,h:Int,out:IntArray):Boolean {workBounds.rect(state,w,h,out);return true}
    private var frameId=0
    var averageRadius=.1;private set
    var coverage=0.0;private set
    private class Body(val id:Int){
        var signature=0L
        var stamp=0;var first=0;var last=0;var count=0;var output=0
        var minX=0f;var maxX=0f;var minY=0f;var maxY=0f;var maxRadius=0f;var radii=0f
    }
    fun texture(index:Int)=when(index){0->input.table.texture;1->field?.textures?.get(0)?:0;2->field?.textures?.get(1)?:0;else->0}
    fun uniform(name:String,value:FloatArray){when(name){
        "uN"->value[0]=input.capacity.toFloat()
        "uFieldBounds"->bounds.copyInto(value)
        "uFieldTexel"->{value[0]=bounds[2]/field!!.width;value[1]=bounds[3]/field!!.height}
    }}
    fun update(frame:RenderFrame,state:OpticalState,footprint:EyepieceFootprint){
        frameId++;islands.begin(frame.opticalLinks?.epoch,state.settings.smoothContour);visible.clear();count=0;workBounds.reset()
        val bounded=state.settings.boundedOptics
        val cells=frame.cells;val n=(cells?.remaining()?:0)/Cells.STRUCT_SIZE
        if(n>next.size){val cap=max(n,next.size*2);next=IntArray(cap)}
        val cx=state.camX.toFloat();val cy=state.camY.toFloat()
        val sx=(state.width*state.scale).toFloat();val sy=(state.height*state.scale).toFloat();val lag=frame.interpolationLag
        var cached:Body?=null
        val ranges=frame.opticalCullRanges;var range=0;var cursor=0
        // Радиус декодируется в диапазоне <= .75 игровых единиц. Запас .9 из
        // точного отсечения ниже включает жгутики; обе позы уже лежат в границах.
        val pad=(Cells.RADIUS_MIN+Cells.RADIUS_SPAN)*UNITS+.9f
        val left=(cx-sx*.5f-pad)/UNITS;val right=(cx+sx*.5f+pad)/UNITS
        val bottom=(cy-sy*.5f-pad)/UNITS;val top=(cy+sy*.5f+pad)/UNITS
        while(cells!=null&&cursor<n){
            val i=cursor
            if(ranges!=null&&range<ranges.count&&i==ranges.first(range)){
                islands.retain(frame.opticalGroups?.get(i)?:1)
                val skip=!ranges.intersects(range,left,bottom,right,top);val end=ranges.end(range++)
                if(skip){cursor=end;continue}
            }
            cursor++
            val o=cells.position()+i*Cells.STRUCT_SIZE;val type=cells.getInt(o+16);val isCell=(type ushr 24 and Cells.FLAG_NOT_CELL)==0
            val x=(cells.getFloat(o)+(cells.getFloat(o+28)-cells.getFloat(o))*lag)*UNITS
            val y=(cells.getFloat(o+4)+(cells.getFloat(o+32)-cells.getFloat(o+4))*lag)*UNITS
            if(!isCell&&(abs(x-cx)>sx*.5f+pad||abs(y-cy)>sy*.5f+pad))continue
            val group=frame.opticalGroups?.get(i)?:1;val id=if(!isCell)Int.MIN_VALUE else if(group!=0)group else -1-i
            var b=cached
            if(b==null||b.id!=id){b=bodies[id];if(b==null){b=Body(id);bodies.put(id,b)};cached=b}
            val r=radius(cells.getInt(o+12))
            if(b.stamp!=frameId){
                islands.retain(id)
                b.stamp=frameId;b.signature=0L;b.first=i;b.count=0;b.radii=0f;b.maxRadius=r
                b.minX=x-r;b.maxX=x+r;b.minY=y-r;b.maxY=y+r;visible.add(b)
            }
            b.signature=b.signature*1000003L+type.toLong()
            b.signature=b.signature*1000003L+(frame.opticalBirthTicks?.get(i)?:-1)
            if(b.count>0)next[b.last]=i;b.last=i;next[i]=-1;b.count++
            b.minX=min(b.minX,x-r);b.maxX=max(b.maxX,x+r);b.minY=min(b.minY,y-r);b.maxY=max(b.maxY,y+r)
            b.maxRadius=max(b.maxRadius,r);b.radii+=r
        }
        val iter=bodies.entries();while(iter.hasNext()){val e=iter.next();if(e.value.stamp!=frameId)iter.remove()}
        var kept=0
        for(v in visible.indices){
            val b=visible[v];val margin=max(.9f,b.maxRadius*4f)
            if(b.maxX<cx-sx*.5f-margin||b.minX>cx+sx*.5f+margin||b.maxY<cy-sy*.5f-margin||b.minY>cy+sy*.5f+margin)continue
            if(bounded)workBounds.body(b.minX,b.minY,b.maxX,b.maxY,b.maxRadius)
            visible[kept++]=b;count+=b.count
        }
        while(visible.size>kept)visible.removeAt(visible.size-1)
        input.begin(count)
        var output=0;var radii=0.0;var area=0.0
        if(cells!=null){
            for(k in visible.indices){val b=visible[k];b.output=output;var i=b.first
                while(i>=0){val o=cells.position()+i*Cells.STRUCT_SIZE;val color=frame.opticalColors?.get(i)?:cells.getInt(o+8)
                    input.add(i,color,b.output,b.id,frame.opticalBirthTicks?.get(i)?:-1);val r=radius(cells.getInt(o+12));radii+=r;area+=r*r*PI;output++;i=next[i]}
            }
        }
        averageRadius=if(count>0)radii/count else .1;coverage=(area/max(.0001f,sx*sy)).coerceIn(0.0,1.0)
        val gl=Gdx.gl30;gl.glDisable(GL_SCISSOR_TEST);gl.glDisable(GL_BLEND);gl.glDisable(GL_CULL_FACE);gl.glDisable(GL_STENCIL_TEST);gl.glDisable(GL_DEPTH_TEST)
        gl.glDisable(GL_DITHER);gl.glColorMask(true,true,true,true);gl.glDepthMask(true)
        if(visible.isEmpty()){
            islands.end()
            // Отсечение выполнено, кэши тел обслужены. Без окружения пустому
            // кадру не нужны ни декодирование клеток, ни полноэкранное поле.
            return
        }
        input.upload(frame,count)
        if(field?.width!=state.width||field?.height!=state.height){field?.dispose();field=OpticalTarget(state.width,state.height,false,true,2)}
        bounds[0]=cx-sx*.5f;bounds[1]=cy-sy*.5f;bounds[2]=sx;bounds[3]=sy
        gl.glClearDepthf(1f);field!!.clear(1f,1f,0f,0f)
        if(cells!=null)for(b in visible){
            val margin=max(.9f,b.maxRadius*4f)
            val draw=footprint.overlapsWorld(b.minX-margin,b.minY-margin,b.maxX+margin,b.maxY+margin,state)
            islands.body(cells,next,b.first,b.count,b.id,b.output,b.signature,frame.opticalLinks,b.maxRadius,draw)
        }
        islands.end();islands.field(field!!,input,state,footprint)
        gl.glBindVertexArray(0);gl.glBindFramebuffer(GL_FRAMEBUFFER,0);gl.glActiveTexture(GL_TEXTURE0)
    }
    private fun radius(shape:Int)=(Cells.RADIUS_MIN+(shape ushr 16 and 255)/255f*Cells.RADIUS_SPAN)*UNITS
    override fun dispose(){islands.dispose();field?.dispose();input.dispose();draw.dispose();bodies.clear()}
    companion object { const val UNITS=.25f }
}
