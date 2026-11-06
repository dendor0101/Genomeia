package io.github.some_example_name.render.microscope

import kotlin.math.abs
import kotlin.math.atan2

/** В клетке с несколькими веерами соединяем каймы через свободный сектор.
 * Грани тела и физические связи остаются исходными. Строится вместе с топологией. */
internal object TissuePinchJoins {
    private data class Ray(val end:Int,val inside:Int,val angle:Double,val cross:Double)

    fun key(center:Int,end:Int)=(center.toLong() shl 32) or end.toLong()

    fun build(mesh:TissueTriangles,degree:IntArray):Map<Long,Pair<Int,Int>> {
        if(degree.none{it>2})return emptyMap()
        val fans=HashMap<Int,MutableList<Ray>>()
        val poses=mesh.poses
        fun add(a:Int,b:Int,c:Int){
            if(degree[a]<=2)return
            val x=(poses[b*4]-poses[a*4]).toDouble()
            val y=(poses[b*4+1]-poses[a*4+1]).toDouble()
            val cross=x*(poses[c*4+1]-poses[a*4+1])-y*(poses[c*4]-poses[a*4])
            fans.getOrPut(a){ArrayList()}.add(Ray(b,c,atan2(y,x),cross))
        }
        for(t in mesh.boundaryBits.indices)for(k in 0..2){
            if(mesh.boundaryBits[t] and (1 shl k)==0)continue
            val a=mesh.indices[t*3+(k+1)%3];val b=mesh.indices[t*3+(k+2)%3];val c=mesh.indices[t*3+k]
            add(a,b,c);add(b,a,c)
        }
        val joins=HashMap<Long,Pair<Int,Int>>()
        for((a,rays) in fans){
            rays.sortBy{it.angle}
            // При уже сложенной/вырожденной сетке однозначной пары может не быть.
            if(rays.indices.any{i->
                val ray=rays[i];val next=rays[(i+1)%rays.size]
                abs(ray.cross)<1e-10 || (ray.cross>0)==(next.cross>0) ||
                    abs(Math.IEEEremainder(next.angle-ray.angle,2*Math.PI))<1e-7
            })continue
            for(i in rays.indices){
                val ray=rays[i]
                val side=if(ray.cross>0) -1 else 1
                val mate=rays[(i+side+rays.size)%rays.size]
                joins[key(a,ray.end)]=mate.end to mate.inside
            }
        }
        return joins
    }
}
