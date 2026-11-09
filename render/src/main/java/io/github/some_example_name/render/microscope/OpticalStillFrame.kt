package io.github.some_example_name.render.microscope

import io.github.some_example_name.render.RenderFrame
import java.nio.ByteBuffer

/** Ключ последнего рассчитанного кадра, около 300 байт. Проверка O(число настроек),
 * без обхода клеток и хэшей мира. uploadCells — договор о неизменности снимка.
 * Экспозиция не входит: финальный post выполняется даже при повторном использовании. */
internal class OpticalStillFrame {
    private var valid=false
    private var cells:ByteBuffer?=null
    private var links:io.github.some_example_name.render.pack.OpticalLinks?=null
    private var groups:IntArray?=null;private var colors:IntArray?=null;private var births:IntArray?=null
    private var position=0;private var limit=0
    private var w=0;private var h=0
    private var x=0.0;private var y=0.0;private var scale=0.0;private var focus=0.0;private var time=0.0
    private var lag=0f;private var clock=0f
    private val values=FloatArray(OpticalParameter.entries.size)
    fun invalidate(){valid=false}
    fun matches(f:RenderFrame,s:OpticalState):Boolean {
        if(!valid||f.uploadCells||links!==f.opticalLinks||cells!==f.cells||groups!==f.opticalGroups||colors!==f.opticalColors||births!==f.opticalBirthTicks)return false
        if(position!=(f.cells?.position()?:0)||limit!=(f.cells?.limit()?:0)||w!=s.width||h!=s.height)return false
        if(x!=s.camX||y!=s.camY||scale!=s.scale||focus!=s.focus||time!=s.time||lag!=f.interpolationLag||clock!=f.time)return false
        for(p in OpticalParameter.entries)if(p!=OpticalParameter.CACHE_PAUSED&&values[p.ordinal]!=p.get(s.settings))return false
        return true
    }
    fun remember(f:RenderFrame,s:OpticalState){
        links=f.opticalLinks;cells=f.cells;groups=f.opticalGroups;colors=f.opticalColors;births=f.opticalBirthTicks
        position=cells?.position()?:0;limit=cells?.limit()?:0;w=s.width;h=s.height
        x=s.camX;y=s.camY;scale=s.scale;focus=s.focus;time=s.time;lag=f.interpolationLag;clock=f.time
        for(p in OpticalParameter.entries)values[p.ordinal]=p.get(s.settings)
        valid=true
    }
}
