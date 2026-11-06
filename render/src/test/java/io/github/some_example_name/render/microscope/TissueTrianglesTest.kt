package io.github.some_example_name.render.microscope

import org.junit.Test
import kotlin.test.*
import java.io.File

class TissueTrianglesTest {
    private fun poses(vararg xy:Float)=FloatArray(xy.size*2).also{p->for(i in xy.indices step 2){p[i*2]=xy[i];p[i*2+1]=xy[i+1];p[i*2+3]=.1f}}
    @Test fun crossingDiagonalsProduceTwoFacesNotFourOverlappingTriangles(){
        val mesh=TissueTriangles(poses(0f,0f,1f,0f,1f,1f,0f,1f),intArrayOf(0,1,1,2,2,3,0,3,0,2,1,3))
        assertEquals(6,mesh.indices.size);assertEquals(1,mesh.rejectedEdges)
        assertEquals(4,mesh.boundaryBits.sumOf{Integer.bitCount(it)})
    }
    @Test fun enclosingTriangleIsNotDrawnOverItsInternalFaces(){
        val mesh=TissueTriangles(poses(0f,0f,2f,0f,1f,2f,1f,.6f),intArrayOf(0,1,1,2,0,2,0,3,1,3,2,3))
        assertEquals(9,mesh.indices.size);assertEquals(0,mesh.rejectedEdges)
        assertEquals(3,mesh.boundaryBits.sumOf{Integer.bitCount(it)})
    }
    @Test fun danglingSpringInsideTriangleDoesNotEraseTheFace(){
        val mesh=TissueTriangles(poses(0f,0f,2f,0f,0f,2f,.4f,.4f),intArrayOf(0,1,1,2,0,2,0,3))
        assertEquals(setOf(0,1,2),mesh.indices.toSet());assertEquals(3,mesh.indices.size)
    }
    @Test fun twoTrianglesConnectedByBridgeKeepBothFaces(){
        val mesh=TissueTriangles(poses(0f,0f,1f,0f,0f,1f,2f,0f,3f,0f,3f,1f),
            intArrayOf(0,1,1,2,0,2,1,3,3,4,4,5,3,5))
        assertEquals(6,mesh.indices.size);assertEquals(6,mesh.boundaryBits.sumOf{Integer.bitCount(it)})
    }
    @Test fun userControlledGlistRetainsTrianglesWithInteriorSpurs(){
        // Клетки и реальные связи из пользовательского F9-снимка 29.10; без террейна.
        val json=com.badlogic.gdx.utils.JsonReader().parse(javaClass.getResourceAsStream("/microscope/controlled-glist-spurs.json"))
        val mesh=TissueTriangles(json.get("poses").asFloatArray(),json.get("edges").asIntArray())
        // 994 замкнутые тройки исходного графа. Проекция удаляла 26 связей
        // и оставляла лишь 966 граней; это не изменение эталона картинки.
        assertEquals(994,mesh.indices.size/3)
        assertEquals(0,mesh.rejectedEdges);assertFalse(mesh.ambiguousTopology)
        val warped=json.get("poses").asFloatArray()
        for(i in 0 until warped.size/4){warped[i*4]=(i%7).toFloat();warped[i*4+1]=(i%3).toFloat()}
        assertContentEquals(mesh.indices,TissueTriangles(warped,json.get("edges").asIntArray()).indices)
        val edges=json.get("edges").asIntArray();val graph=HashSet<Long>()
        fun key(a:Int,b:Int)=(minOf(a,b).toLong() shl 32) or maxOf(a,b).toLong()
        for(i in edges.indices step 2)graph.add(key(edges[i],edges[i+1]))
        for(t in mesh.indices.indices step 3)for(k in 0..2)
            assertTrue(key(mesh.indices[t+k],mesh.indices[t+(k+1)%3]) in graph,"No invented physical connections")
    }
    @Test fun foldingAndFlatteningDoNotChangeConnectedFaces(){
        val edges=intArrayOf(0,1,1,2,0,2,1,3,2,3)
        val open=TissueTriangles(poses(0f,0f,1f,0f,0f,1f,1f,1f),edges)
        val folded=TissueTriangles(poses(0f,0f,1f,0f,0f,1f,-.2f,.2f),edges)
        val flat=TissueTriangles(poses(0f,0f,1f,0f,0f,1f,.5f,.5f),edges)
        assertEquals(6,open.indices.size)
        assertContentEquals(open.indices,folded.indices);assertContentEquals(open.indices,flat.indices)
        assertContentEquals(open.boundaryBits,folded.boundaryBits)
        assertEquals(0,folded.rejectedEdges)
    }
    @Test fun duplicateAndReversedLinksDoNotDuplicateFaces(){
        val mesh=TissueTriangles(poses(0f,0f,1f,0f,0f,1f),intArrayOf(1,0,0,1,1,2,2,1,2,0,0,2))
        assertEquals(3,mesh.indices.size)
    }
    @Test fun missingConnectionsAreNotInvented(){
        val mesh=TissueTriangles(poses(0f,0f,1f,0f,1f,1f,0f,1f),intArrayOf(0,1,1,2,2,3,0,3))
        assertTrue(mesh.indices.isEmpty())
    }
    @Test fun tenThousandVerticesHaveLinearMeshSizeAndContinuousMaterialCoordinates(){
        val n=100;val p=FloatArray(n*n*4);val edges=ArrayList<Int>()
        for(y in 0 until n)for(x in 0 until n){val i=y*n+x;p[i*4]=x*.2f;p[i*4+1]=y*.2f;p[i*4+3]=.1f
            if(x+1<n){edges.add(i);edges.add(i+1)};if(y+1<n){edges.add(i);edges.add(i+n)}
            if(x+1<n&&y+1<n){edges.add(i);edges.add(i+n+1)}}
        val mesh=TissueTriangles(p,edges.toIntArray())
        assertEquals((n-1)*(n-1)*6,mesh.indices.size);assertEquals(0,mesh.rejectedEdges)
        assertEquals(n*n*5,mesh.vertices.size)
        assertEquals((n-1)*4,mesh.boundaryBits.sumOf{Integer.bitCount(it)})
        assertTrue(mesh.vertices[(n/2*n+n/2)*5+3]>.05f)
    }
}
