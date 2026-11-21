package io.github.some_example_name.render.pack

/** Только граф изображения: CSR в порядке инстансов. 4 B/вершину + 4 B/соседа,
 * около 280 КиБ на 10k клеток с шестью соседями. Пишется потоком в слот снимка;
 * симуляция не читает эти данные. Рендер обходит только диапазоны видимых тел. */
class OpticalLinks {
    /** Общий токен трёх слотов снимка. Новый мир сбрасывает только кэш изображения. */
    var epoch:Any=Any()
    var offsets=IntArray(1); private set
    var neighbors=IntArray(0); private set
    /** Устойчивые ключи только для развёртки, не индексы в меняющемся снимке.
     * 8 B/клетку (78 КиБ/10k), заполняются последовательно вместе с CSR. */
    var vertexIds=LongArray(0); private set
    /** UV в покое и радиус масштаба в единицах мира. Нулевой радиус = нет эталона.
     * Ещё 12 B/клетку в снимке; не отправляется на GPU каждый кадр. */
    var materialRest=FloatArray(0); private set
    var count=0; private set
    var size=0; private set
    fun begin(n:Int,capacity:Int){
        if(offsets.size<n+1)offsets=IntArray(maxOf(n+1,offsets.size*2))
        if(vertexIds.size<n)vertexIds=LongArray(maxOf(n,vertexIds.size*2))
        if(materialRest.size<n*3)materialRest=FloatArray(maxOf(n*3,materialRest.size*2))
        if(neighbors.size<capacity)neighbors=IntArray(maxOf(capacity,neighbors.size*2))
        count=n;size=0
    }
    fun vertex(i:Int,id:Long=i.toLong()+1){offsets[i]=size;vertexIds[i]=id;materialRest[i*3]=0f;materialRest[i*3+1]=0f;materialRest[i*3+2]=0f}
    fun add(j:Int){neighbors[size++]=j}
    /** Соседи вершины уже записаны прямо в neighbors с места size: сдвинуть size на [n]. */
    fun advance(n:Int){size+=n}
    fun end(){offsets[count]=size}
    /** Запись по воркерам: место вершины уже в offsets, соседи пишутся прямо в neighbors. */
    fun vertexParallel(i:Int,id:Long=i.toLong()+1){vertexIds[i]=id;materialRest[i*3]=0f;materialRest[i*3+1]=0f;materialRest[i*3+2]=0f}
    fun endParallel(total:Int){size=total;offsets[count]=total}
    companion object {
        /** Группа не-клеток террейна (RenderFrame.opticalGroups): одно тело ткани на весь террейн,
         * всегда в расфокусе над плоскостью фокуса. Номера клеток — организм + 1 и −частица − 1: не пересекаются. */
        const val TERRAIN_GROUP=Int.MIN_VALUE+1
    }
}
