package io.github.some_example_name.render.microscope

import kotlin.test.*

class TissueRimTest {
    private fun square()=TissueTriangles(floatArrayOf(0f,0f,0f,.2f,1f,0f,0f,.2f,1f,1f,0f,.2f,0f,1f,0f,.2f),
        intArrayOf(0,1,1,2,2,3,3,0,0,2))
    @Test fun onlyPerimeterGetsRimAndCoreDrawsLast(){
        val m=square();val r=TissueRim(m)
        assertEquals(4,r.edgeCount);assertEquals(2+4*6,r.triangles.size/4)
        assertEquals(4*5,(r.nodes.size-m.count*4)/8) // после записей соседей — общие стыки
        val core=r.triangles.size-m.indices.size/3*4
        for(t in m.boundaryBits.indices)for(k in 0..2)assertEquals(m.indices[t*3+k].toFloat(),r.triangles[core+t*4+k])
        for(i in r.triangles.indices step 4)for(k in 0..2)assertTrue(r.triangles[i+k].toInt() in 0 until r.rest.size/4)
        assertTrue(r.rest.all{it.isFinite()});assertTrue(r.nodes.all{it.isFinite()})
    }
    @Test fun outerTextureCoordinatesContinueBeyondTheCellCenters(){
        val m=square();val r=TissueRim(m)
        for(i in 0 until m.count){assertEquals(m.vertices[i*5],r.rest[i*4]);assertEquals(m.vertices[i*5+1],r.rest[i*4+1])}
        assertTrue((m.count until r.rest.size/4).any{r.rest[it*4]<0f})
        assertTrue((m.count until r.rest.size/4).any{r.rest[it*4]>5f})
        for(i in m.count until r.rest.size/4)assertEquals(r.nodes[m.count*4+(i-m.count)*8],r.rest[i*4+3])
    }
    @Test fun eachSmoothBoundaryVertexReferencesItsOwnOuterCorner(){
        val m=square();val r=TissueRim(m)
        for(i in 0 until m.count){
            assertEquals(1f,r.nodes[i*4+2])
            val corner=r.nodes[i*4+3].toInt()
            assertTrue(corner in m.count until r.rest.size/4)
            val o=m.count*4+(corner-m.count)*8
            assertEquals(i.toFloat(),r.nodes[o]);assertEquals(0f,r.nodes[o+5]);assertEquals(1f,r.nodes[o+7])
        }
    }
    @Test fun pinchedMeshesJoinTheRimAcrossTheEmptySectors(){
        val p=floatArrayOf(0f,0f,0f,.1f,1f,0f,0f,.1f,0f,1f,0f,.1f,-1f,0f,0f,.1f,0f,-1f,0f,.1f)
        val m=TissueTriangles(p,intArrayOf(0,1,1,2,0,2,0,3,3,4,0,4));val r=TissueRim(m)
        assertEquals(6,r.edgeCount);assertEquals(30,(r.nodes.size-m.count*4)/8)
        assertEquals(-1f,r.nodes[2]) // точка касания не должна переехать к одному из островов
        for(i in m.count*4 until r.nodes.size step 8)if(r.nodes[i]==0f){
            val end=r.nodes[i+1].toInt()
            val mate=r.nodes[i+3].toInt()
            assertEquals(5-end,mate)
        }
        assertTrue(r.rest.all{it.isFinite()})
    }
    @Test fun realFoldedScenePreservesEveryCoreTriangleAndBoundedRimSize(){
        val json=com.badlogic.gdx.utils.JsonReader().parse(javaClass.getResourceAsStream("/microscope/controlled-glist-spurs.json"))
        val m=TissueTriangles(json.get("poses").asFloatArray(),json.get("edges").asIntArray());val r=TissueRim(m)
        assertEquals(994,m.indices.size/3)
        assertEquals(m.boundaryBits.sumOf{Integer.bitCount(it)},r.edgeCount)
        assertEquals(m.indices.size/3+r.edgeCount*6,r.triangles.size/4)
        assertTrue(r.nodes.size<=m.count*4+r.edgeCount*6*8);assertTrue(r.rest.all{it.isFinite()})
    }
}
