package io.github.some_example_name.render.microscope

import kotlin.test.*

class TissueContourUvTest {
    @Test fun sharedFilamentUvRootRemainsAttachedAtBothSmoothingLimits(){
        val p=floatArrayOf(0f,0f,0f,.2f,1f,0f,0f,.2f,0f,1f,0f,.2f,2f,0f,0f,.2f)
        val e=intArrayOf(0,1,1,2,0,2,1,3);val mesh=TissueTriangles(p,e);val rim=TissueRim(mesh,null,e)
        val packed=TissueContourUv.pack(rim.rest,rim.nodes,mesh.count);var roots=0
        for(i in mesh.count until rim.rest.size/4){val o=mesh.count*4+(i-mesh.count)*8
            if(rim.nodes[o+7]>=0f||rim.nodes[o].toInt()!=1)continue
            roots++
            for(level in 0..1)for(axis in 0..1){
                val shift=packed[rim.rest.size+4+level*2+axis]-rim.rest[4+axis]
                assertEquals(rim.rest[i*4+axis]+shift,packed[rim.rest.size+i*4+level*2+axis],1e-5f)
            }
        }
        assertEquals(2,roots)
    }
    @Test fun meetingRimVerticesHaveIdenticalUvAtBothSmoothingLimits(){
        val p=floatArrayOf(0f,0f,0f,.2f,1f,0f,0f,.2f,1f,1f,0f,.2f,0f,1f,0f,.2f)
        val mesh=TissueTriangles(p,intArrayOf(0,1,1,2,2,3,0,3,0,2))
        val rim=TissueRim(mesh);val packed=TissueContourUv.pack(rim.rest,rim.nodes,mesh.count)
        assertContentEquals(rim.rest,packed.copyOf(rim.rest.size))
        val ends=HashMap<Pair<Int,Int>,FloatArray>();var matches=0
        for(o in mesh.count*4 until rim.nodes.size step 8)if(rim.nodes[o+5]==1f){
            val a=rim.nodes[o].toInt();val b=rim.nodes[o+1].toInt()
            val id=mesh.count+(o-mesh.count*4)/8
            val uv=packed.copyOfRange(rim.rest.size+id*4,rim.rest.size+id*4+4)
            val old=ends.put(minOf(a,b) to maxOf(a,b),uv)
            if(old!=null){assertContentEquals(old,uv);matches++}
        }
        assertEquals(4,matches);assertTrue(packed.all{it.isFinite()})
    }
}
