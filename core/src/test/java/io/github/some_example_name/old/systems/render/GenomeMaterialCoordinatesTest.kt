package io.github.some_example_name.old.systems.render

import io.github.some_example_name.old.systems.genomics.genome.Genome
import io.github.some_example_name.old.systems.genomics.genome.GenomePreview
import io.github.some_example_name.render.pack.OpticalLinks
import org.junit.Test
import kotlin.test.*

class GenomeMaterialCoordinatesTest {
    private fun genome(x:Float)=Genome(1,"test",preview=GenomePreview(
        x=floatArrayOf(x,2f),y=floatArrayOf(-1f,1f),radius=floatArrayOf(.1f,.3f),cellGenomeId=intArrayOf(100,7000)))
    @Test fun stableIdsUseWholeGenomeScaleAndReplacingGenomeInvalidatesCache(){
        val genomes=mutableListOf(genome(1f));val cache=GenomeMaterialCoordinates(genomes)
        val graph=OpticalLinks();graph.begin(2,0);graph.vertex(0);graph.vertex(1)
        cache.write(graph,0,0,7000);cache.write(graph,1,0,100)
        assertEquals(10f,graph.materialRest[0],.0001f);assertEquals(5f,graph.materialRest[3],.0001f)
        assertEquals(.2f,graph.materialRest[2],.0001f)
        genomes[0]=genome(3f);cache.write(graph,1,0,100)
        assertEquals(15f,graph.materialRest[3],.0001f)
        graph.vertex(1);cache.write(graph,1,0,999)
        assertEquals(0f,graph.materialRest[5])
        genomes[0]=Genome(1,"legacy");cache.write(graph,0,0,100) // No preview: no exception.
    }
}
