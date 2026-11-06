package io.github.some_example_name.render.microscope

import com.badlogic.gdx.utils.FloatArray as Floats
import kotlin.math.*

/** Холодная топология дисков и трубок. Все положения при движении — на GPU.
 * Рёбра, уже принадлежащие поверхности, здесь не дублируются. */
internal object TissueLooseGeometry {
    class Topology(val mesh:TissueTriangles,edges:IntArray){
        fun key(a:Int,b:Int)=(min(a,b).toLong() shl 32) or max(a,b).toLong()
        val used=HashSet<Long>();val covered=BooleanArray(mesh.count)
        val parent=IntArray(mesh.count){it}
        fun root(v:Int):Int{var i=v;while(parent[i]!=i){parent[i]=parent[parent[i]];i=parent[i]};return i}
        init { for(t in mesh.indices.indices step 3)for(k in 0..2){
            val a=mesh.indices[t+k];val b=mesh.indices[t+(k+1)%3]
            used.add(key(a,b));covered[a]=true;parent[root(b)]=root(a)
        } }
        // Отвергнутая диагональ внутри уже построенной ткани — физическая
        // пружина, а не отдельная трубка. Мост между разными островами оставляем.
        fun loose(a:Int,b:Int)=a!=b&&!used.contains(key(a,b))&&
            !(covered[a]&&covered[b]&&root(a)==root(b))
        val neighbors=Array(mesh.count){ArrayList<Int>()}
        init { for(e in edges.indices step 2){val a=edges[e];val b=edges[e+1]
            if(loose(a,b)){neighbors[a].add(b);neighbors[b].add(a)}} }
    }
    fun append(mesh:TissueTriangles,edges:IntArray,transforms:FloatArray?,verts:Floats,faces:Floats,nodes:Floats,topology:Topology,rootLeft:IntArray,rootRight:IntArray){
        val covered=topology.covered;val neighbors=topology.neighbors
        fun face(a:Int,b:Int,c:Int){faces.add(a.toFloat(),b.toFloat(),c.toFloat(),-1f)}
        fun node(a:Int,b:Int,c:Int,t:Float,single:Boolean):Int{
            val offset=offset(mesh.poses,a,b,c,t,single)
            val r=mesh.poses[a*4+3];val o=a*4
            val du=if(transforms==null)offset[0]/mesh.radius else transforms[o]*offset[0]+transforms[o+1]*offset[1]
            val dv=if(transforms==null)offset[1]/mesh.radius else transforms[o+2]*offset[0]+transforms[o+3]*offset[1]
            val id=verts.size/4
            // Отрицательная высота — метка погружённого в ткань корня.
            // Здесь нет внешней стенки: нулевая глубина оставляла тёмный шов.
            val relief=if(covered[a])-max(.018f,mesh.vertices[a*5+3]) else .018f
            verts.add(mesh.vertices[a*5]+du*r,mesh.vertices[a*5+1]+dv*r,relief,a.toFloat())
            nodes.add(a.toFloat(),b.toFloat(),c.toFloat(),0f)
            nodes.add(t,1f,if(single)1f else 0f,-1f)
            return id
        }
        val left=HashMap<Long,Int>();val right=HashMap<Long,Int>()
        fun directed(a:Int,b:Int)=(a.toLong() shl 32) or b.toLong()
        for(a in 0 until mesh.count){
            val list=neighbors[a]
            if(list.isEmpty()){
                if(covered[a])continue
                verts.items[a*4+2]=.055f;verts.items[a*4+3]=-mesh.poses[a*4+3]
                val ring=IntArray(16){node(a,a,a,it/16f,true)}
                for(i in ring.indices)face(a,ring[i],ring[(i+1)%16])
                continue
            }
            if(rootLeft[a]>=0&&rootRight[a]>=0){
                val b=list.single();left[directed(a,b)]=rootLeft[a];right[directed(a,b)]=rootRight[a];continue
            }
            if(!covered[a]){verts.items[a*4+2]=.055f;verts.items[a*4+3]=-mesh.poses[a*4+3]}
            list.sortBy{atan2(mesh.poses[it*4+1]-mesh.poses[a*4+1],mesh.poses[it*4]-mesh.poses[a*4])}
            for(i in list.indices){val b=list[i];val c=list[(i+1)%list.size]
                // Фиксированная сетка дуги остаётся действительной при изгибе.
                var first=-1;var previous=-1
                for(j in 0..8){val id=node(a,b,c,j/8f,false)
                    // В корне отростка ткань уже закрывает торец. Его полудиск
                    // давал второй контур/рельеф внутри существующей поверхности.
                    if(j==0)first=id else if(!covered[a])face(a,previous,id)
                    previous=id
                }
                left[directed(a,b)]=first;right[directed(a,c)]=previous
            }
        }
        for(e in edges.indices step 2){val a=edges[e];val b=edges[e+1]
            if(!topology.loose(a,b))continue
            val al=left.getValue(directed(a,b));val ar=right.getValue(directed(a,b))
            val bl=left.getValue(directed(b,a));val br=right.getValue(directed(b,a))
            face(a,b,br);face(a,br,al);face(a,ar,bl);face(a,bl,b)
        }
    }
    fun offset(p:FloatArray,a:Int,b:Int,c:Int,t:Float,single:Boolean):FloatArray{
        if(single)return floatArrayOf(cos(t*2f*PI.toFloat()),sin(t*2f*PI.toFloat()))
        val ab=atan2(p[b*4+1]-p[a*4+1],p[b*4]-p[a*4])
        val ac=atan2(p[c*4+1]-p[a*4+1],p[c*4]-p[a*4])
        val gap=if(b==c)2*PI.toFloat() else (ac-ab+2*PI.toFloat())%(2*PI.toFloat())
        val n=floatArrayOf(-sin(ab),cos(ab));val m=floatArrayOf(sin(ac),-cos(ac))
        if(gap<PI){val d=max(.65f,1f+n[0]*m[0]+n[1]*m[1]);return floatArrayOf((n[0]+m[0])/d,(n[1]+m[1])/d)}
        val angle=ab+PI.toFloat()/2+(gap-PI.toFloat())*t
        return floatArrayOf(cos(angle),sin(angle))
    }
}
