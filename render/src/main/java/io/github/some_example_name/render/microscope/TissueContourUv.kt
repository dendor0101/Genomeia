package io.github.some_example_name.render.microscope

/** Два предела UV для ползунка: 100% и 200%, одна vec4 на вершину.
 * Готовятся при изменении топологии. В кадре — только интерполяция на GPU.
 * В середине общего ребра UV должны совпасть, когда туда сходятся две каймы. */
internal object TissueContourUv {
    fun pack(rest:FloatArray,nodes:FloatArray,count:Int):FloatArray {
        val corner=IntArray(count){it}
        for(o in count*4 until nodes.size step 8)if(nodes[o+7]>.5f&&nodes[o+5]==0f)
            corner[nodes[o].toInt()]=count+(o-count*4)/8
        fun eligible(i:Int)=nodes[i*4+2]>.5f
        fun uv(i:Int,axis:Int,outside:Boolean,toward:Int=-1):Float{
            var id=if(outside)corner[i] else i
            if(outside&&nodes[i*4+2]< -1.5f){
                id=nodes[i*4].toInt()
                if(nodes[count*4+(id-count)*8+1].toInt()!=toward)id=nodes[i*4+1].toInt()
            }
            return rest[id*4+axis]
        }
        fun relaxed(i:Int,axis:Int,outside:Boolean,extra:Boolean,toward:Int=-1):Float{
            val a=uv(i,axis,outside,toward)
            if(!extra||!eligible(i))return a
            val b=nodes[i*4].toInt();val p=nodes[i*4+1].toInt()
            return a+.25f*((uv(b,axis,outside,i)+uv(p,axis,outside,i))*.5f-a)
        }
        val packed=rest.copyOf(rest.size*2)
        for(i in 0 until rest.size/4)for(level in 0..1)for(axis in 0..1){
            var value=rest[i*4+axis]
            if(i<count&&eligible(i)){
                val b=nodes[i*4].toInt();val p=nodes[i*4+1].toInt()
                value=.75f*relaxed(i,axis,false,level==1)+.125f*(relaxed(b,axis,false,level==1)+relaxed(p,axis,false,level==1))
            }else if(i>=count){
                val o=count*4+(i-count)*8
                if(nodes[o+7]>.5f){
                    val a=nodes[o].toInt();val b=nodes[o+1].toInt();val p=nodes[o+3].toInt()
                    val control=relaxed(a,axis,true,level==1)
                    val start=(relaxed(p,axis,true,level==1,a)+control)*.5f
                    val end=(relaxed(b,axis,true,level==1,a)+control)*.5f
                    val t=.5f+.5f*nodes[o+5]
                    value=(1f-t)*(1f-t)*start+2f*t*(1f-t)*control+t*t*end
                }else if(nodes[o+7]<0f){
                    // Корень нити двигается вместе с вершиной ткани, включая UV.
                    val a=nodes[o].toInt()
                    if(eligible(a)){
                        val b=nodes[a*4].toInt();val p=nodes[a*4+1].toInt()
                        value+=.75f*relaxed(a,axis,false,level==1)+.125f*(relaxed(b,axis,false,level==1)+relaxed(p,axis,false,level==1))-rest[a*4+axis]
                    }
                }
            }
            packed[rest.size+i*4+level*2+axis]=value
        }
        return packed
    }
}
