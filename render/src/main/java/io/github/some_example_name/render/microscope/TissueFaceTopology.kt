package io.github.some_example_name.render.microscope

/** Треугольная поверхность определяется связями, а не проекцией замятого тела.
 * Холодный O(E·degree²) обход только при смене связей. CSR + счётчики ~400 КиБ/10k
 * вершин с шестью соседями, последовательные строки по несколько десятков байт.
 * null означает неоднозначный граф: K4 или ребро более чем в двух тройках.
 * Для такого графа нужны явные грани/исходная развёртка; координаты сами по себе
 * не позволяют отличить сложенную поверхность от пересечённых лишних связей. */
internal object TissueFaceTopology {
    fun triangles(count:Int,edges:IntArray):IntArray? {
        val offsets=IntArray(count+1)
        for(i in edges.indices step 2){val a=edges[i];val b=edges[i+1]
            if(a !in 0 until count||b !in 0 until count||a==b)continue
            offsets[a+1]++;offsets[b+1]++
        }
        for(i in 1..count)offsets[i]+=offsets[i-1]
        val cursor=offsets.copyOf();val neighbors=IntArray(offsets[count])
        for(i in edges.indices step 2){val a=edges[i];val b=edges[i+1]
            if(a !in 0 until count||b !in 0 until count||a==b)continue
            neighbors[cursor[a]++]=b;neighbors[cursor[b]++]=a
        }
        // Уплотняем дубликаты, чтобы одинаковые физические связи не создавали грани дважды.
        val ends=IntArray(count)
        for(a in 0 until count){val start=offsets[a];val end=offsets[a+1]
            neighbors.sort(start,end);var used=start;var last=-1
            for(i in start until end){val b=neighbors[i];if(b!=last){neighbors[used++]=b;last=b}}
            ends[a]=used
        }
        val uses=IntArray(neighbors.size);val triangles=com.badlogic.gdx.utils.IntArray()
        for(a in 0 until count)for(ab in offsets[a] until ends[a]){
            val b=neighbors[ab];if(b<=a)continue
            var ac=ab+1;var bc=offsets[b]
            while(ac<ends[a]&&bc<ends[b]){
                val c=neighbors[ac];val other=neighbors[bc]
                when {
                    c<other->ac++
                    c>other->bc++
                    else->{
                        // K4 даёт четыре тройки на одних четырёх клетках: по графу
                        // это может быть тетраэдр, пересечённый квадрат или три грани
                        // вокруг центра. Нужна исходная развёртка, не четыре наложения.
                        for(cd in offsets[c] until ends[c]){
                            val d=neighbors[cd];if(d<=c)continue
                            if(neighbors.binarySearch(d,offsets[a],ends[a])>=0&&
                                neighbors.binarySearch(d,offsets[b],ends[b])>=0)return null
                        }
                        if(++uses[ab]>2||++uses[ac]>2||++uses[bc]>2)return null
                        triangles.add(a,b,c);ac++;bc++
                    }
                }
            }
        }
        return triangles.toArray()
    }
}
