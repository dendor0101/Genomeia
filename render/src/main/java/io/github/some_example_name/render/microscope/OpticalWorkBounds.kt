package io.github.some_example_name.render.microscope

import kotlin.math.*

/** Объединённая область видимых клеток; обновляется в уже существующем обходе.
 * Запас включает поддержку материала (1.5R), отростки, два PSF и дисперсию.
 * Полные clear обязательны: при движении камеры старое содержимое не должно оживать. */
internal class OpticalWorkBounds {
    private var left=Float.POSITIVE_INFINITY;private var bottom=Float.POSITIVE_INFINITY
    private var right=Float.NEGATIVE_INFINITY;private var top=Float.NEGATIVE_INFINITY
    fun reset(){left=Float.POSITIVE_INFINITY;bottom=left;right=Float.NEGATIVE_INFINITY;top=right}
    fun add(x:Float,y:Float,r:Float){val pad=max(.2f,r*4f);left=min(left,x-pad);right=max(right,x+pad);bottom=min(bottom,y-pad);top=max(top,y+pad)}
    fun body(x0:Float,y0:Float,x1:Float,y1:Float,r:Float){
        val pad=r*3f+.2f;left=min(left,x0-pad);bottom=min(bottom,y0-pad);right=max(right,x1+pad);top=max(top,y1+pad)
    }
    fun rect(state:OpticalState,w:Int,h:Int,out:IntArray){
        if(left>right){out.fill(0);return}
        val scale=state.scale*state.width/w
        val pad=ceil(40.0*w/(state.width*state.settings.layerScale)+16.0*w/state.width).toInt()
        val x0=(floor(w*.5+(left-state.camX)/scale).toInt()-pad).coerceIn(0,w)
        val y0=(floor(h*.5+(bottom-state.camY)/scale).toInt()-pad).coerceIn(0,h)
        val x1=(ceil(w*.5+(right-state.camX)/scale).toInt()+pad).coerceIn(0,w)
        val y1=(ceil(h*.5+(top-state.camY)/scale).toInt()+pad).coerceIn(0,h)
        out[0]=x0;out[1]=y0;out[2]=max(0,x1-x0);out[3]=max(0,y1-y0)
    }
}
