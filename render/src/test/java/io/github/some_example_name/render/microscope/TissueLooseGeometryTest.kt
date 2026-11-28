package io.github.some_example_name.render.microscope
import org.junit.Test
import kotlin.test.*
import kotlin.math.*

class TissueLooseGeometryTest {
    @Test fun attachedFilamentDoesNotCloseItsRootInsideTheTissue(){
        val p=floatArrayOf(0f,0f,0f,.2f,1f,0f,0f,.2f,0f,1f,0f,.2f,2f,0f,0f,.2f)
        val e=intArrayOf(0,1,1,2,0,2,1,3)
        val mesh=TissueTriangles(p,e);val rim=TissueRim(mesh,null,e)
        fun looseOwner(i:Int):Int {
            if(i<mesh.count)return -1
            val o=mesh.count*4+(i-mesh.count)*8
            return if(rim.nodes[o+7]<0f)rim.nodes[o].toInt() else -1
        }
        var rootCaps=0;var tipCaps=0
        for(t in rim.triangles.indices step 4){
            val a=rim.triangles[t].toInt();val b=rim.triangles[t+1].toInt();val c=rim.triangles[t+2].toInt()
            if(a==1&&looseOwner(b)==1&&looseOwner(c)==1)rootCaps++
            if(a==3&&looseOwner(b)==3&&looseOwner(c)==3)tipCaps++
        }
        assertEquals(0,rootCaps);assertEquals(8,tipCaps)
        var shared=0
        for(i in mesh.count until rim.rest.size/4){
            val owner=looseOwner(i)
            if(owner==1){
                shared++
                // Одна и та же вершина замыкает ткань и трубку, без наложения.
                var uses=0
                for(t in rim.triangles.indices step 4)for(k in 0..2){
                    val x=rim.triangles[t+k].toInt();val y=rim.triangles[t+(k+1)%3].toInt()
                    if((x==1&&y==i)||(x==i&&y==1))uses++
                }
                assertEquals(2,uses,"Joint edge must have exactly two incident triangles")
            }
            if(owner==3)assertTrue(rim.rest[i*4+2]>0f,"Free tip must retain its outer contour")
        }
        assertEquals(2,shared)
    }
    @Test fun collinearSurfaceAndFilamentStillHaveTwoDistinctJointSides(){
        val p=floatArrayOf(0f,0f,0f,.2f,1f,0f,0f,.2f,0f,-1f,0f,.2f,0f,1f,0f,.2f)
        val e=intArrayOf(0,1,1,2,0,2,0,3);val mesh=TissueTriangles(p,e);val rim=TissueRim(mesh,null,e)
        assertEquals(-2f,rim.nodes[2])
        val left=rim.nodes[0].toInt();val right=rim.nodes[1].toInt()
        assertNotEquals(left,right)
        assertTrue(rim.rest[left*4]<rim.rest[right*4])
    }
    @Test fun crossedDiagonalInsideTissueMustNotBecomeAnotherTube(){
        val p=floatArrayOf(0f,0f,0f,.2f,1f,0f,0f,.2f,1f,1f,0f,.2f,0f,1f,0f,.2f)
        val e=intArrayOf(0,1,1,2,2,3,0,3,0,2,1,3)
        val mesh=TissueTriangles(p,e)
        assertEquals(2,mesh.indices.size/3)
        val surface=TissueRim(mesh);val withLinks=TissueRim(mesh,null,e)
        assertContentEquals(surface.triangles,withLinks.triangles,"Rejected internal diagonal creates a duplicate optical surface")
        assertContentEquals(surface.rest,withLinks.rest)
    }
    @Test fun diskAndStraightChainHaveAreaWithoutOverlappingCaps(){
        for(n in 1..5){
            val p=FloatArray(n*4);for(i in 0 until n){p[i*4]=i*3f;p[i*4+3]=1f}
            val e=IntArray((n-1)*2){if(it%2==0)it/2 else it/2+1}
            val mesh=TissueTriangles(p,e);val rim=TissueRim(mesh,null,e)
            assertTrue(rim.triangles.isNotEmpty())
            var area=0.0
            for(t in rim.triangles.indices step 4){
                val a=rim.triangles[t].toInt()*4;val b=rim.triangles[t+1].toInt()*4;val c=rim.triangles[t+2].toInt()*4
                area+=abs((rim.rest[b]-rim.rest[a])*(rim.rest[c+1]-rim.rest[a+1])-(rim.rest[b+1]-rim.rest[a+1])*(rim.rest[c]-rim.rest[a]))*.5
            }
            val expected=6.0*(n-1)+PI
            assertEquals(expected,area,.09,"area for $n cells")
            assertTrue(rim.rest.all{it.isFinite()})
        }
    }
    @Test fun realBridgeBetweenTwoTriangularIslandsIsKept(){
        val p=floatArrayOf(0f,0f,0f,.2f,1f,0f,0f,.2f,0f,1f,0f,.2f,
            3f,0f,0f,.2f,4f,0f,0f,.2f,4f,1f,0f,.2f)
        val e=intArrayOf(0,1,1,2,0,2,3,4,4,5,3,5,1,3)
        val mesh=TissueTriangles(p,e)
        val rim=TissueRim(mesh,null,e)
        assertTrue((rim.triangles.indices step 4).any{t->
            val ids=(0..2).map{rim.triangles[t+it].toInt()}
            1 in ids&&3 in ids
        },"A bridge must connect both islands even when shared joints reduce the triangle count")
    }
}
