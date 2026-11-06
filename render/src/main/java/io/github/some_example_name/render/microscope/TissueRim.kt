package io.github.some_example_name.render.microscope

import com.badlogic.gdx.utils.FloatArray as Floats
import kotlin.math.*

/** Только холодная топология края. Живые позиции/нормали считает vertex shader.
 * Шесть треугольников на граничное ребро, а не оболочки вокруг каждой клетки.
 * ~5 узлов × (16 B rest + 32 B описание) + 96 B индексов = ~336 B/ребро:
 * при периметре 400 рёбер около 132 КиБ GPU-кэша, без загрузки за кадр.
 * Соседи для сглаживания добавляют 16 B на исходную клетку.
 * Развёртка продолжается наружу один раз, поэтому не плавает при движении. */
internal class TissueRim(private val mesh:TissueTriangles,private val uvTransforms:FloatArray?=null,looseEdges:IntArray=intArrayOf()) {
    val rest:FloatArray
    val triangles:FloatArray
    val nodes:FloatArray
    val edgeCount:Int
    private fun key(a:Int,b:Int)=(min(a,b).toLong() shl 32) or max(a,b).toLong()
    init {
        val verts=Floats();val faces=Floats();val descriptors=Floats()
        // Одна запись на исходную вершину: два соседа, признак, узел внешнего угла.
        // Это холодный кэш, при движении используются только живые позиции GPU.
        repeat(mesh.count){descriptors.add(0f,0f,0f,0f)}
        for(i in 0 until mesh.count){val o=i*5
            verts.add(mesh.vertices[o],mesh.vertices[o+1],mesh.vertices[o+3],mesh.vertices[o+4])}
        val incident=HashMap<Long,Long>()
        for(t in mesh.boundaryBits.indices){
            val o=t*3;faces.add(mesh.indices[o].toFloat(),mesh.indices[o+1].toFloat(),mesh.indices[o+2].toFloat(),mesh.boundaryBits[t].toFloat())
            for(k in 0..2){val edge=key(mesh.indices[o+k],mesh.indices[o+(k+1)%3]);val old=incident[edge]?:0L
                incident[edge]=if(old==0L)(t+1).toLong() else old or ((t+1).toLong() shl 32)}
        }
        // Обход веера треугольников у вершины находит другое ребро ТОЙ ЖЕ границы.
        // Это также запасной путь для уже сложенного, геометрически неоднозначного веера.
        fun otherBoundary(a:Int,b:Int,face:Int):Pair<Int,Int>{
            var previous=b;var current=face
            repeat(mesh.indices.size/3){
                var next=-1
                for(k in 0..2){val v=mesh.indices[current*3+k];if(v!=a&&v!=previous)next=v}
                if(next<0)return b to mesh.indices[face*3]
                val pair=incident[key(a,next)]?:0L
                val first=(pair and 0xffffffffL).toInt()-1;val second=(pair ushr 32).toInt()-1
                if(second<0)return next to previous
                val following=if(first==current)second else first
                if(following<0||following==face)return b to next
                previous=next;current=following
            }
            return b to a
        }
        val joins=HashMap<Long,Int>()
        val loose=TissueLooseGeometry.Topology(mesh,looseEdges)
        val boundaryDegree=IntArray(mesh.count)
        for(tri in mesh.boundaryBits.indices)for(k in 0..2)if((mesh.boundaryBits[tri] and (1 shl k))!=0){
            boundaryDegree[mesh.indices[tri*3+(k+1)%3]]++
            boundaryDegree[mesh.indices[tri*3+(k+2)%3]]++
        }
        // Две общие вершины вместо наложенных друг на друга трубки и каймы.
        // Только холодная топология; при движении стык вычисляется на GPU.
        val rootLeft=IntArray(mesh.count){-1};val rootRight=IntArray(mesh.count){-1}
        val pinchJoins=TissuePinchJoins.build(mesh,boundaryDegree)
        fun rimNeighbor(a:Int,b:Int,tri:Int):Pair<Int,Int>{
            if(boundaryDegree[a]>2)pinchJoins[TissuePinchJoins.key(a,b)]?.let{return it}
            return otherBoundary(a,b,tri)
        }
        val maxRadius=(0 until mesh.count).maxOfOrNull{mesh.poses[it*4+3]}?:mesh.radius
        fun node(a:Int,b:Int,c:Int,other:Int,otherFace:Int,t:Float):Int {
            val meta=a*4
            if(boundaryDegree[a]==2&&loose.neighbors[a].size==1&&
                branchSide(mesh.poses,a,b,c,loose.neighbors[a][0])!=branchSide(mesh.poses,a,other,otherFace,loose.neighbors[a][0])){
                val branch=loose.neighbors[a][0];val p=mesh.poses
                val side=if(branchSide(p,a,b,c,branch)>0f)rootLeft else rootRight
                if(side[a]>=0)return side[a]
                val off=branchOffset(p,a,b,c,branch,maxRadius)
                val o=a*4;val m=uvTransforms
                val du=if(m==null)off[0]/mesh.radius else m[o]*off[0]+m[o+1]*off[1]
                val dv=if(m==null)off[1]/mesh.radius else m[o+2]*off[0]+m[o+3]*off[1]
                val id=verts.size/4
                verts.add(mesh.vertices[a*5]+du,mesh.vertices[a*5+1]+dv,.018f,a.toFloat())
                descriptors.add(a.toFloat(),b.toFloat(),c.toFloat(),branch.toFloat())
                descriptors.add(branchSide(p,a,b,c,branch),0f,1f,-2f)
                descriptors.items[meta+2]=-1f;side[a]=id
                return id
            }
            if(descriptors.items[meta+2]==0f){
                descriptors.items[meta]=b.toFloat();descriptors.items[meta+1]=other.toFloat();descriptors.items[meta+2]=1f
            }else if(descriptors.items[meta+2]>0f&&
                !((descriptors.items[meta]==b.toFloat()&&descriptors.items[meta+1]==other.toFloat())||
                  (descriptors.items[meta]==other.toFloat()&&descriptors.items[meta+1]==b.toFloat())))descriptors.items[meta+2]=-1f
            val join=(a.toLong() shl 32) or min(b,other).toLong()
            if(t==0f)joins[join]?.let{return it}
            val offset=offset(mesh.poses,a,b,c,other,otherFace,t)
            val m=uvTransforms;val o=a*4
            // Кайма продолжает ту же развёртку после поворота/роста тела.
            val du=if(m==null)offset[0]/mesh.radius.coerceAtLeast(.001f) else m[o]*offset[0]+m[o+1]*offset[1]
            val dv=if(m==null)offset[1]/mesh.radius.coerceAtLeast(.001f) else m[o+2]*offset[0]+m[o+3]*offset[1]
            val u=mesh.vertices[a*5]+du*maxRadius
            val v=mesh.vertices[a*5+1]+dv*maxRadius
            val noise=1f+.035f*(sin(u*2.3f+v*1.7f)*.65f+cos(u*.9f-v*2.9f)*.35f)
            val id=verts.size/4
            verts.add(mesh.vertices[a*5]+(u-mesh.vertices[a*5])*noise,
                mesh.vertices[a*5+1]+(v-mesh.vertices[a*5+1])*noise,.018f,a.toFloat())
            descriptors.add(a.toFloat(),b.toFloat(),c.toFloat(),other.toFloat())
            descriptors.add(otherFace.toFloat(),t,noise,0f)
            if(t==0f){joins[join]=id;descriptors.items[meta+3]=id.toFloat()}
            return id
        }
        fun face(a:Int,b:Int,c:Int){faces.add(a.toFloat(),b.toFloat(),c.toFloat(),-1f)}
        var edges=0
        for(tri in mesh.boundaryBits.indices)for(k in 0..2)if((mesh.boundaryBits[tri] and (1 shl k))!=0){
            val a=mesh.indices[tri*3+(k+1)%3];val b=mesh.indices[tri*3+(k+2)%3];val c=mesh.indices[tri*3+k]
            val pa=rimNeighbor(a,b,tri);val pb=rimNeighbor(b,a,tri)
            val a0=node(a,b,c,pa.first,pa.second,0f);val a1=node(a,b,c,pa.first,pa.second,.5f);val a2=node(a,b,c,pa.first,pa.second,1f)
            val b0=node(b,a,c,pb.first,pb.second,0f);val b1=node(b,a,c,pb.first,pb.second,.5f);val b2=node(b,a,c,pb.first,pb.second,1f)
            if(a0!=a1)face(a,a0,a1);if(a1!=a2)face(a,a1,a2)
            face(a,a2,b2);face(a,b2,b)
            if(b2!=b1)face(b,b2,b1);if(b1!=b0)face(b,b1,b0);edges++
        }
        for(a in 0 until mesh.count)if(rootLeft[a]>=0&&rootRight[a]>=0){
            descriptors.items[a*4]=rootLeft[a].toFloat();descriptors.items[a*4+1]=rootRight[a].toFloat();descriptors.items[a*4+2]=-2f
        }
        TissueLooseGeometry.append(mesh,looseEdges,uvTransforms,verts,faces,descriptors,loose,rootLeft,rootRight)
        // В точке касания двух контуров общего направления сглаживания нет.
        for(i in mesh.count*4 until descriptors.size step 8)if(descriptors.items[i+7]>=0f)
            descriptors.items[i+7]=if(descriptors.items[descriptors.items[i].toInt()*4+2]>0f)1f else 0f
        rest=verts.toArray()
        // Кайма под исходной тканью: на замятой поверхности она не должна
        // записывать свой нулевой край поверх уже занятой внутренней области.
        val core=mesh.indices.size/3*4
        triangles=FloatArray(faces.size)
        faces.items.copyInto(triangles,0,core,faces.size)
        faces.items.copyInto(triangles,faces.size-core,0,core)
        nodes=descriptors.toArray();edgeCount=edges
    }
    companion object {
        private fun branchSide(p:FloatArray,a:Int,b:Int,c:Int,branch:Int):Float {
            val bx=p[b*4]-p[a*4];val by=p[b*4+1]-p[a*4+1]
            val sign=if(bx*(p[c*4+1]-p[a*4+1])-by*(p[c*4]-p[a*4])<0f)-1f else 1f
            val dx=p[branch*4]-p[a*4];val dy=p[branch*4+1]-p[a*4+1]
            val side=-(bx*dx+by*dy)*sign
            return if(if(abs(side)>1e-6f)side>0f else dx*by-dy*bx>0f)1f else -1f
        }
        internal fun branchOffset(p:FloatArray,a:Int,b:Int,c:Int,branch:Int,radius:Float):FloatArray {
            val bx=p[b*4]-p[a*4];val by=p[b*4+1]-p[a*4+1]
            val sign=if(bx*(p[c*4+1]-p[a*4+1])-by*(p[c*4]-p[a*4])<0f)-1f else 1f
            val length=hypot(bx,by).coerceAtLeast(1e-6f)
            val nx=by*sign/length;val ny=-bx*sign/length
            val dx=p[branch*4]-p[a*4];val dy=p[branch*4+1]-p[a*4+1]
            val side=branchSide(p,a,b,c,branch)
            val dl=hypot(dx,dy).coerceAtLeast(1e-6f)
            val mx=-dy/dl*side;val my=dx/dl*side;val dot=nx*mx+ny*my
            val sum=(radius+p[a*4+3])*.5f/max(.35f,1f+dot)
            val diff=(radius-p[a*4+3])*.5f/max(.35f,1f-dot)
            return floatArrayOf((nx+mx)*sum+(nx-mx)*diff,(ny+my)*sum+(ny-my)*diff)
        }
        /** Та же конструкция, что на GPU; здесь только исходная развёртка. */
        internal fun offset(p:FloatArray,a:Int,b:Int,c:Int,other:Int,otherFace:Int,t:Float):FloatArray {
            fun normal(b:Int,c:Int):FloatArray{
                val x=p[b*4]-p[a*4];val y=p[b*4+1]-p[a*4+1]
                val cross=x*(p[c*4+1]-p[a*4+1])-y*(p[c*4]-p[a*4])
                val scale=(if(cross<0f)-1f else 1f)/sqrt(x*x+y*y).coerceAtLeast(1e-6f)
                return floatArrayOf(y*scale,-x*scale)
            }
            val n=normal(b,c);val q=normal(other,otherFace)
            val sx=n[0]+q[0];val sy=n[1]+q[1];val length=sqrt(sx*sx+sy*sy)
            val convex=(p[other*4]-p[a*4])*n[0]+(p[other*4+1]-p[a*4+1])*n[1]<=1e-6f
            if(convex){
                val x=(if(length>1e-5f)sx/length else n[0])*(1-t)+n[0]*t
                val y=(if(length>1e-5f)sy/length else n[1])*(1-t)+n[1]*t
                val d=sqrt(x*x+y*y).coerceAtLeast(1e-6f);return floatArrayOf(x/d,y/d)
            }
            val d=max(.7f,1f+n[0]*q[0]+n[1]*q[1]);return floatArrayOf(sx/d,sy/d)
        }
    }
}
