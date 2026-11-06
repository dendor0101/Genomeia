package io.github.some_example_name.render.microscope

import org.junit.Test
import kotlin.test.*

class TissueRestCoordinatesTest {
    @Test fun referenceUVIsIndependentOfGrowthHistoryRotationCompressionAndSnapshotOrder(){
        val reference=floatArrayOf(0f,0f,.1f, 10f,0f,.1f, 0f,10f,.1f, 10f,10f,.1f)
        val allEdges=intArrayOf(0,1,1,2,0,2,1,3,2,3)
        var old:TissueRestCoordinates?=null
        for(step in 0..40){
            val n=if(step==0)3 else 4
            val edges=if(n==3)intArrayOf(0,1,1,2,0,2) else allEdges
            val p=FloatArray(n*4)
            for(i in 0 until n){
                val x=reference[i*3]*.1f;val y=reference[i*3+1]*.1f
                p[i*4]=20f+kotlin.math.cos(step.toFloat())*x-kotlin.math.sin(step.toFloat())*y
                p[i*4+1]=30f+(kotlin.math.sin(step.toFloat())*x+kotlin.math.cos(step.toFloat())*y)*.001f
                p[i*4+3]=.1f+step*.002f
            }
            val current=TissueRestCoordinates(TissueTriangles(p,edges),LongArray(n){it+10L},edges,old,reference.copyOf(n*3))
            for(i in 0 until n)for(k in 0..1)assertEquals(reference[i*3+k].toRawBits(),current.uv[i*2+k].toRawBits())
            assertEquals(.1f,current.radius);assertTrue(current.transforms.all{it.isFinite()&&kotlin.math.abs(it)<=40.001f})
            old=current
        }
        val order=intArrayOf(3,1,0,2);val reversed=FloatArray(12)
        for(i in order.indices)reference.copyInto(reversed,i*3,order[i]*3,order[i]*3+3)
        val rest=TissueRestCoordinates(mesh(0f,0f,1f,0f,0f,1f,1f,1f),LongArray(4){order[it]+10L},allEdges,old,reversed)
        for(i in order.indices)for(k in 0..1)assertEquals(reference[order[i]*3+k],rest.uv[i*2+k])
    }
    @Test fun growthNextToCompressedTriangleCannotExplodeTextureScale(){
        val edges=intArrayOf(0,1,1,2,0,2)
        val old=TissueRestCoordinates(mesh(0f,0f,1f,0f,0f,1f),longArrayOf(1,2,3),edges,null)
        val grownEdges=intArrayOf(0,1,1,2,0,2,0,3,1,3)
        val grown=TissueTriangles(floatArrayOf(0f,0f,0f,.1f, 1f,0f,0f,.1f,
            0f,.01f,0f,.1f, .5f,1f,0f,.1f),grownEdges)
        val rest=TissueRestCoordinates(grown,longArrayOf(1,2,3,4),grownEdges,old)
        assertTrue(kotlin.math.abs(rest.uv[7])<=40f,"New UV exploded: ${rest.uv[7]}")
        for(i in old.uv.indices)assertEquals(old.uv[i],rest.uv[i])
    }
    private fun mesh(vararg xy:Float):TissueTriangles {
        val p=FloatArray(xy.size*2)
        for(i in xy.indices step 2){p[i*2]=xy[i];p[i*2+1]=xy[i+1];p[i*2+3]=.1f}
        return TissueTriangles(p,intArrayOf(0,1,1,2,0,2))
    }
    @Test fun growthAfterRotationKeepsExistingVerticesBitExactAndContinuesTheirUV(){
        val edges=intArrayOf(0,1,1,2,0,2)
        val before=mesh(0f,0f,1f,0f,0f,1f)
        val old=TissueRestCoordinates(before,longArrayOf(10,20,30),edges,null)
        // Снимок переставлен, тело повёрнуто на 90°, увеличено вдвое и перенесено.
        // Новая вершина — (1,1) в исходной развёртке, её слот В НАЧАЛЕ снимка.
        val p=floatArrayOf(8f,22f,0f,.2f, 8f,20f,0f,.2f, 10f,20f,0f,.2f, 10f,22f,0f,.2f)
        val grownEdges=intArrayOf(1,2,2,3,1,3,0,1,0,3)
        val grown=TissueTriangles(p,grownEdges)
        val uv=TissueRestCoordinates(grown,longArrayOf(40,30,10,20),grownEdges,old)
        for((now,was)in listOf(1 to 2,2 to 0,3 to 1))for(k in 0..1)
            assertEquals(old.uv[was*2+k].toRawBits(),uv.uv[now*2+k].toRawBits())
        assertEquals(10f,uv.uv[0],.0001f);assertEquals(10f,uv.uv[1],.0001f)
        // Кайма использует тот же поворот, а не текущие мировые XY.
        assertEquals(0f,uv.transforms[8],.0001f);assertEquals(5f,uv.transforms[9],.0001f)
        assertEquals(-5f,uv.transforms[10],.0001f)
    }
    @Test fun reusedSlotWithNewGenerationDoesNotInheritOldTexture(){
        val edges=intArrayOf(0,1,1,2,0,2)
        val old=TissueRestCoordinates(mesh(0f,0f,1f,0f,0f,1f),longArrayOf(1,2,3),edges,null)
        val changed=TissueRestCoordinates(mesh(0f,0f,1f,0f,0f,2f),longArrayOf(1,2,4),edges,old)
        assertEquals(20f,changed.uv[5],.0001f)
        assertEquals(old.uv[0],changed.uv[0]);assertEquals(old.uv[2],changed.uv[2])
    }
    @Test fun changedRadiusAndBoundsCannotReseedUnchangedPolygons(){
        val edges=intArrayOf(0,1,1,2,0,2)
        val ids=longArrayOf(100,200,300)
        val old=TissueRestCoordinates(mesh(0f,0f,1f,0f,0f,1f),ids,edges,null)
        val next=mesh(-20f,5f,-15f,5f,-20f,10f)
        val rest=TissueRestCoordinates(next,ids,edges,old)
        assertContentEquals(old.uv,rest.uv)
    }
}
