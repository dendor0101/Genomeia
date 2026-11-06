package io.github.some_example_name.render.microscope

/** Развёртка переживает рост и переупорядочивание снимка. Только холодная сборка графа:
 * сохранённые UV копируются побитово, новые продолжают локальное отображение соседей.
 * 8 B ключ + 8 B UV/вершину (~156 КиБ/10k); временный CSR и матрицы ~400 КиБ/10k.
 * Нет работы с UV в обычном кадре и нет таблицы по всему миру. */
internal class TissueRestCoordinates(mesh:TissueTriangles, val ids:LongArray, edges:IntArray, old:TissueRestCoordinates?, reference:FloatArray?=null) {
    // Эталон поступает из запечённого генома. Поза на момент рождения не определяет UV:
    // тело может расти, двигаться и складываться одновременно.
    val radius:Float=reference?.indices?.firstOrNull{it%3==2&&reference[it]>0f}?.let{reference[it]}
        ?:old?.radius?:mesh.radius.coerceAtLeast(.001f)
    val uv=FloatArray(ids.size*2)
    val transforms=FloatArray(ids.size*4)
    init {
        val known=BooleanArray(ids.size)
        // Поиск только при изменении топологии; размер — конкретное тело, не мир.
        val previous=HashMap<Long,Int>(old?.ids?.size?:0)
        old?.ids?.forEachIndexed { i,id->previous[id]=i }
        val queue=IntArray(ids.size);var head=0;var tail=0
        for(i in ids.indices){
            val j=previous[ids[i]]
            if(reference!=null&&reference[i*3+2]>0f){
                uv[i*2]=reference[i*3];uv[i*2+1]=reference[i*3+1];known[i]=true;queue[tail++]=i
            }else if(j!=null){uv[i*2]=old!!.uv[j*2];uv[i*2+1]=old.uv[j*2+1];known[i]=true;queue[tail++]=i}
            else {uv[i*2]=mesh.vertices[i*5];uv[i*2+1]=mesh.vertices[i*5+1]}
        }
        val offsets=IntArray(ids.size+1)
        for(e in edges)offsets[e+1]++
        for(i in ids.indices)offsets[i+1]+=offsets[i]
        val cursor=offsets.copyOf();val neighbors=IntArray(edges.size)
        for(e in edges.indices step 2){val a=edges[e];val b=edges[e+1];neighbors[cursor[a]++]=b;neighbors[cursor[b]++]=a}
        fun matrix(a:Int){
            var xx=0f;var xy=0f;var yy=0f;var ux=0f;var uy=0f;var vx=0f;var vy=0f
            for(e in offsets[a] until offsets[a+1]){val b=neighbors[e];if(!known[b])continue
                val x=mesh.poses[b*4]-mesh.poses[a*4];val y=mesh.poses[b*4+1]-mesh.poses[a*4+1]
                val u=uv[b*2]-uv[a*2];val v=uv[b*2+1]-uv[a*2+1]
                xx+=x*x;xy+=x*y;yy+=y*y;ux+=u*x;uy+=u*y;vx+=v*x;vy+=v*y
            }
            val det=xx*yy-xy*xy;val o=a*4
            if(det>.0025f*(xx+yy)*(xx+yy)){
                transforms[o]=(ux*yy-uy*xy)/det;transforms[o+1]=(uy*xx-ux*xy)/det
                transforms[o+2]=(vx*yy-vy*xy)/det;transforms[o+3]=(vy*xx-vx*xy)/det
                val a0=transforms[o];val b0=transforms[o+1];val c0=transforms[o+2];val d0=transforms[o+3]
                val norm=a0*a0+b0*b0+c0*c0+d0*d0;val area=a0*d0-b0*c0
                val largest=.5f*(norm+kotlin.math.sqrt((norm*norm-4f*area*area).coerceAtLeast(0f)))
                // Не переносим огромный градиент смятого треугольника в новую ткань/кайму.
                if(largest.isFinite()&&largest<=16f/(radius*radius)&&area*area>=largest*.0625f/(radius*radius))return
            }
            if(xx+yy>1e-10f){
                // У старых геномов без превью: устойчивый поворот+ограниченный масштаб.
                var c=(ux+vy)/(xx+yy);var s=(vx-uy)/(xx+yy)
                val length=kotlin.math.sqrt(c*c+s*s)
                if(length>1e-10f&&length.isFinite()){
                    val scale=length.coerceIn(.25f/radius,4f/radius)/length;c*=scale;s*=scale
                }else{c=1f/radius;s=0f}
                transforms[o]=c;transforms[o+1]=-s;transforms[o+2]=s;transforms[o+3]=c
            }else {transforms[o]=1f/radius;transforms[o+1]=0f;transforms[o+2]=0f;transforms[o+3]=1f/radius}
        }
        // Мультистартовый BFS: O(V+E), в том числе когда за снимок выросло много клеток.
        while(head<tail){val a=queue[head++];matrix(a)
            for(e in offsets[a] until offsets[a+1]){val b=neighbors[e];if(known[b])continue
                val x=mesh.poses[b*4]-mesh.poses[a*4];val y=mesh.poses[b*4+1]-mesh.poses[a*4+1];val o=a*4
                uv[b*2]=uv[a*2]+transforms[o]*x+transforms[o+1]*y
                uv[b*2+1]=uv[a*2+1]+transforms[o+2]*x+transforms[o+3]*y
                known[b]=true;queue[tail++]=b
            }
        }
        known.fill(true)
        for(i in ids.indices){
            mesh.vertices[i*5]=uv[i*2];mesh.vertices[i*5+1]=uv[i*2+1];matrix(i)
        }
    }
}
