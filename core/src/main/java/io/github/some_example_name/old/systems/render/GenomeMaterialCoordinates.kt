package io.github.some_example_name.old.systems.render

import io.github.some_example_name.old.systems.genomics.genome.Genome
import io.github.some_example_name.old.systems.genomics.genome.GenomePreview
import io.github.some_example_name.render.pack.OpticalLinks
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap

/** Развёртка из запечённого тела, не из текущей (возможно смятой) позы.
 * Один индекс на геном, общий всем его организмам; ~16 B/клетку генома.
 * В существующем проходе снимка — один поиск и три записи, без работы с геометрией.
 * Симуляция этот кэш не читает. Замена генома/превью инвалидирует только его запись. */
internal class GenomeMaterialCoordinates(private val genomes:List<Genome>) {
    private class Entry(val preview:GenomePreview?) {
        val indices=Int2IntOpenHashMap().apply { defaultReturnValue(-1) }
        val radius:Float
        init {
            val p=preview
            radius=if(p!=null&&p.cellCount>0&&p.radius.size==p.cellCount)
                (p.radius.sumOf{it.toDouble()}/p.cellCount).toFloat().coerceAtLeast(.001f) else 0f
            if(p!=null&&radius>0f&&p.y.size==p.cellCount&&p.cellGenomeId.size==p.cellCount)
                for(i in 0 until p.cellCount)if(p.x[i].isFinite()&&p.y[i].isFinite())indices.put(p.cellGenomeId[i],i)
        }
    }
    private val entries=ArrayList<Entry?>()
    fun write(graph:OpticalLinks,vertex:Int,genome:Int,cellId:Int){
        val source=genomes.getOrNull(genome)?:return
        while(entries.size<=genome)entries.add(null)
        var entry=entries[genome]
        if(entry==null||entry.preview!==source.preview){entry=Entry(source.preview);entries[genome]=entry}
        val i=entry.indices.get(cellId)
        if(i<0)return
        val p=entry.preview!!;val o=vertex*3
        graph.materialRest[o]=p.x[i]/entry.radius
        graph.materialRest[o+1]=p.y[i]/entry.radius
        graph.materialRest[o+2]=entry.radius
    }
}
