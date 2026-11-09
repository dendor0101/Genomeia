package io.github.some_example_name.render.microscope

import kotlin.math.*

/** Холодная сборка при изменении графа, не за кадр. Только существующие связи.
 * Для треугольной поверхности берём тройки реальных связей независимо от координат.
 * Старый плоский обход оставлен только для неоднозначного графа (K4 или 3+ граней на ребро),
 * например двух диагоналей квадрата: там без исходной развёртки нельзя выбрать грани.
 * Вершина GPU: restXY, номер живой клетки, высота, расстояние = 20 B; ~200 КиБ/10k.
 * Индексы ~240 КиБ/10k. Временные списки и пространственные корзины после сборки уходят.
 */
internal class TissueTriangles(val poses:FloatArray, edges:IntArray) {
    val count=poses.size/4
    val radius=(0 until count).sumOf{poses[it*4+3].toDouble()}.toFloat()/max(1,count)
    val minX=(0 until count).minOfOrNull{poses[it*4]}?:0f
    val minY=(0 until count).minOfOrNull{poses[it*4+1]}?:0f
    val vertices:FloatArray
    val indices:IntArray
    val boundaryBits:IntArray
    var rejectedEdges=0; private set
    var ambiguousTopology=false; private set
    private fun x(i:Int)=poses[i*4]
    private fun y(i:Int)=poses[i*4+1]
    private fun onInterior(a:Int,b:Int,p:Int):Boolean {
        if(p==a||p==b||abs(area(a,b,p))>1e-10)return false
        return (x(p)-x(a))*(x(p)-x(b))+(y(p)-y(a))*(y(p)-y(b))<0f
    }
    private fun area(a:Int,b:Int,c:Int)=(x(b)-x(a)).toDouble()*(y(c)-y(a))-(y(b)-y(a)).toDouble()*(x(c)-x(a))
    init {
        val topology=TissueFaceTopology.triangles(count,edges)
        if(topology!=null){indices=topology}else{
            ambiguousTopology=true
            data class Edge(val a:Int,val b:Int,val length:Float)
            val ordered=ArrayList<Edge>()
            for(i in edges.indices step 2){val a=edges[i];val b=edges[i+1]
                if(a in 0 until count&&b in 0 until count&&a<b){val dx=x(a)-x(b);val dy=y(a)-y(b);if(dx*dx+dy*dy>1e-12f)ordered.add(Edge(a,b,dx*dx+dy*dy))}}
            ordered.sortWith(compareBy<Edge>{it.length}.thenBy{it.a}.thenBy{it.b})
            val step=max(radius*2f,sqrt(ordered.lastOrNull()?.length?:0f))
            val buckets=HashMap<Long,MutableList<Int>>()
            val accepted=ArrayList<Edge>()
            val neighbors=Array(count){ArrayList<Int>()}
            fun key(ix:Int,iy:Int)=(ix.toLong() shl 32) xor (iy.toLong() and 0xffffffffL)
            for(e in ordered){
                if(neighbors[e.a].contains(e.b))continue
                val lx=floor((min(x(e.a),x(e.b))-minX)/step).toInt();val hx=floor((max(x(e.a),x(e.b))-minX)/step).toInt()
                val ly=floor((min(y(e.a),y(e.b))-minY)/step).toInt();val hy=floor((max(y(e.a),y(e.b))-minY)/step).toInt()
                var crossing=false
                for(iy in ly..hy)for(ix in lx..hx)for(j in buckets[key(ix,iy)]?:emptyList()){
                    val f=accepted[j]
                    if(max(x(e.a),x(e.b))<min(x(f.a),x(f.b))||max(x(f.a),x(f.b))<min(x(e.a),x(e.b))||
                        max(y(e.a),y(e.b))<min(y(f.a),y(f.b))||max(y(f.a),y(f.b))<min(y(e.a),y(e.b)))continue
                    if(onInterior(e.a,e.b,f.a)||onInterior(e.a,e.b,f.b)){crossing=true;break}
                    if(e.a==f.a||e.a==f.b||e.b==f.a||e.b==f.b)continue
                    val ab=area(e.a,e.b,f.a)*area(e.a,e.b,f.b)
                    val cd=area(f.a,f.b,e.a)*area(f.a,f.b,e.b)
                    if(ab<=0.0&&cd<=0.0){crossing=true;break}
                }
                if(crossing){rejectedEdges++;continue}
                val id=accepted.size;accepted.add(e);neighbors[e.a].add(e.b);neighbors[e.b].add(e.a)
                for(iy in ly..hy)for(ix in lx..hx)buckets.getOrPut(key(ix,iy)){ArrayList()}.add(id)
            }
            for(i in neighbors.indices)neighbors[i].sortWith(compareBy<Int>{atan2(y(it)-y(i),x(it)-x(i))}.thenBy{it})
            val visited=Array(count){BooleanArray(neighbors[it].size)}
            val faces=ArrayList<Int>()
            // Тупиковая пружина обходится туда и обратно. Она не разрезает грань:
            // выделяем простые циклы, иначе треугольник с такой веточкой имел size=5
            // и целиком исчезал. Буферы общие для обхода, O(V+E), новых связей нет.
            val path=IntArray(count);val slots=IntArray(count){-1};var pathSize=0
            fun visit(v:Int){
                val old=slots[v]
                if(old<0){slots[v]=pathSize;path[pathSize++]=v;return}
                if(pathSize-old==3&&area(path[old],path[old+1],path[old+2])>1e-10){
                    faces.add(path[old]);faces.add(path[old+1]);faces.add(path[old+2])
                }
                while(pathSize>old+1)slots[path[--pathSize]]=-1
            }
            for(a in neighbors.indices)for(slot in neighbors[a].indices){
                if(visited[a][slot])continue
                var from=a;var at=slot
                do {
                    if(visited[from][at])break
                    visited[from][at]=true;visit(from)
                    val to=neighbors[from][at];val back=neighbors[to].indexOf(from)
                    at=(back+neighbors[to].size-1)%neighbors[to].size;from=to
                }while(from!=a||at!=slot)
                visit(from)
                while(pathSize>0)slots[path[--pathSize]]=-1
            }
            indices=faces.toIntArray()
        }
        // Граничные вершины получаем из рёбер принятых граней; BFS по самой сетке.
        val uses=HashMap<Long,Int>()
        val mesh=Array(count){ArrayList<Int>()}
        fun edgeKey(a:Int,b:Int)=(min(a,b).toLong() shl 32) or max(a,b).toLong()
        for(t in indices.indices step 3)for(k in 0..2){val a=indices[t+k];val b=indices[t+(k+1)%3];val key=edgeKey(a,b)
            uses[key]=(uses[key]?:0)+1;if(!mesh[a].contains(b)){mesh[a].add(b);mesh[b].add(a)}}
        boundaryBits=IntArray(indices.size/3)
        for(t in boundaryBits.indices)for(k in 0..2){
            if(uses[edgeKey(indices[t*3+(k+1)%3],indices[t*3+(k+2)%3])]==1)boundaryBits[t]=boundaryBits[t] or (1 shl k)
        }
        val distance=IntArray(count){-1};val queue=IntArray(count);var head=0;var tail=0
        for((key,n)in uses)if(n==1){val a=(key ushr 32).toInt();val b=key.toInt()
            if(distance[a]<0){distance[a]=0;queue[tail++]=a};if(distance[b]<0){distance[b]=0;queue[tail++]=b}}
        while(head<tail){val a=queue[head++];for(b in mesh[a])if(distance[b]<0){distance[b]=distance[a]+1;queue[tail++]=b}}
        vertices=FloatArray(count*5)
        for(i in 0 until count){val d=max(0,distance[i]).toFloat();val o=i*5
            vertices[o]=(x(i)-minX)/max(radius,.001f);vertices[o+1]=(y(i)-minY)/max(radius,.001f)
            vertices[o+2]=i.toFloat();vertices[o+3]=.055f*sqrt(1f-exp(-d*.35f));vertices[o+4]=d*radius
        }
    }
}
