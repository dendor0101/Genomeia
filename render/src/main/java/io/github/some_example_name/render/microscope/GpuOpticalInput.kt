package io.github.some_example_name.render.microscope

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL30.*
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.render.RenderFrame
import io.github.some_example_name.render.pack.CellInstanceBuffer as Cells
import java.nio.ByteBuffer

/** На GPU передаётся только видимая выборка. Интерполяция, угол, радиус и HTML-строки
 * по-прежнему вычисляются GPU. CPU копирует подряд идущие инстансы одним memcpy.
 * Индексы + mapping + raw: 60 байт/видимую клетку (~586 КиБ на 10k), потоковый доступ.
 * Резерв текстур не обрабатывается: после дальнего вида работают только активные строки. */
internal class GpuOpticalInput(materials:IntArray,private val draw:GpuOpticalDraw):Disposable {
    private val raw=Gdx.gl.glGenTexture()
    private val mapping=Gdx.gl.glGenTexture()
    private val decode=OpticalProgram("vs_gpu_data.vert","fs_gpu_data.frag")
    private var source:ByteBuffer?=null
    private var sourceOffset=-1
    private var sourceCount=-1
    private var previousCount=-1
    private var selectionChanged=true
    private var indices=IntArray(0)
    private var packed=BufferUtils.newByteBuffer(0)
    private var map=BufferUtils.newIntBuffer(0)
    private var rows=0
    lateinit var table:OpticalTarget;private set
    val capacity get()=rows*256
    init {
        decode.use()
        val kinds=IntArray(256){materials.getOrElse(it){27}}
        Gdx.gl.glUniform4iv(decode.shader.fetchUniformLocation("uMaterials[0]",true),64,kinds,0)
        decode.v2("uRadiusRange",Cells.RADIUS_MIN,Cells.RADIUS_SPAN)
    }
    fun begin(count:Int){
        val required=Integer.highestOneBit(((count+255)/256).coerceAtLeast(1)-1).coerceAtLeast(1)*2
        selectionChanged=count!=previousCount
        if(required>rows){
            if(rows>0){table.dispose()}
            rows=required;map=BufferUtils.newIntBuffer(capacity*4)
            indices=IntArray(capacity);packed=BufferUtils.newByteBuffer(capacity*Cells.STRUCT_SIZE)
            table=OpticalTarget(256,rows*6,internalFormat=GL_RGBA32F)

            allocate(mapping,GL_RGBA32UI,GL_RGBA_INTEGER,256,rows)
            allocate(raw,GL_R32UI,GL_RED_INTEGER,1280,rows*2)
            selectionChanged=true
        }
        map.clear()
    }
    fun add(source:Int,color:Int,anchor:Int,group:Int,birthTick:Int=-1){
        val index=map.position()/4
        if(indices[index]!=source)selectionChanged=true
        indices[index]=source
        map.put(birthTick).put(color).put(anchor).put(group)
    }
    fun upload(frame:RenderFrame,count:Int){
        val gl=Gdx.gl30;val input=frame.cells;val n=(input?.remaining()?:0)/Cells.STRUCT_SIZE
        gl.glActiveTexture(GL_TEXTURE0)
        if(input!=null&&count>0&&(frame.uploadCells||selectionChanged||source!==input||n!=sourceCount||input.position()!=sourceOffset)){
            // Не duplicate на каждую клетку: временно сужаем исходный буфер и обязательно
            // восстанавливаем его диапазон. Снимок принадлежит GL-потоку до конца кадра.
            val position=input.position();val limit=input.limit();packed.clear()
            var first=0
            while(first<count){
                var end=first+1
                while(end<count&&indices[end]==indices[end-1]+1)end++
                input.limit(position+indices[end-1]*Cells.STRUCT_SIZE+Cells.STRUCT_SIZE)
                input.position(position+indices[first]*Cells.STRUCT_SIZE)
                packed.put(input)
                input.position(position);input.limit(limit)
                first=end
            }
            packed.flip();gl.glBindTexture(GL_TEXTURE_2D,raw)
            val full=count/128;val tail=count%128
            if(full>0)gl.glTexSubImage2D(GL_TEXTURE_2D,0,0,0,1280,full,GL_RED_INTEGER,GL_UNSIGNED_INT,packed)
            if(tail>0){packed.position(full*128*Cells.STRUCT_SIZE);gl.glTexSubImage2D(GL_TEXTURE_2D,0,0,full,tail*10,1,GL_RED_INTEGER,GL_UNSIGNED_INT,packed)}
            source=input;sourceCount=n;sourceOffset=position
        }
        previousCount=count
        map.flip();gl.glBindTexture(GL_TEXTURE_2D,mapping)
        val full=count/256;val tail=count%256
        if(full>0)gl.glTexSubImage2D(GL_TEXTURE_2D,0,0,0,256,full,GL_RGBA_INTEGER,GL_UNSIGNED_INT,map)
        if(tail>0){map.position(full*256*4);gl.glTexSubImage2D(GL_TEXTURE_2D,0,0,full,tail,1,GL_RGBA_INTEGER,GL_UNSIGNED_INT,map)}
        val activeRows=((count+255)/256).coerceAtLeast(1)
        table.bind();decode.use();decode.texture("uRaw",raw,0);decode.texture("uMapping",mapping,1)
        decode.i("uCount",count);decode.i("uRows",rows);decode.i("uActiveRows",activeRows)
        decode.f("uLag",frame.interpolationLag);draw.cells(6)

    }
    private fun allocate(texture:Int,format:Int,channels:Int,w:Int,h:Int){
        val gl=Gdx.gl30;gl.glBindTexture(GL_TEXTURE_2D,texture)
        gl.glTexImage2D(GL_TEXTURE_2D,0,format,w,h,0,channels,GL_UNSIGNED_INT,null)
        gl.glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);gl.glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST)
        gl.glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);gl.glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE)
    }
    override fun dispose(){if(rows>0){table.dispose()};decode.dispose();Gdx.gl.glDeleteTexture(raw);Gdx.gl.glDeleteTexture(mapping)}
}
