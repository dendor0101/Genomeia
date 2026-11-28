package io.github.some_example_name.render.microscope

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL30.*
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.Disposable
import kotlin.math.*

/** Bound the live screen-space passes. The polygon circumscribes the visible disk;
 * its guard keeps samples needed by filters at the soft edge of the field. */
internal class EyepieceFootprint : Disposable {
    private val sides = 24
    private val vertices = BufferUtils.newFloatBuffer(2*sides)
    var buffer = 0
        private set
    private var width = 0
    private var height = 0
    private var radius = 0.0
    var enabled = false
        private set

    var cropLayers = false
        private set

    fun update(s: OpticalState) {
        val w=s.width; val h=s.height
        // The field reaches zero at edge + .006 in the post shader. Both 16-texel
        // Gaussian passes can contribute diagonally, even on the smallest focus tier.
        val minScale = s.settings.layerScale * .5
        val guard = (sqrt(2.0)*16 + 6) / minScale + 16
        val r = (.986 - .26*s.settings.vignette)*h + guard
        enabled = !s.settings.mask && s.settings.opticalView==0 && r < hypot(w*.5,h*.5)
        if(!enabled || (w==width && h==height && r==radius)) return
        width=w; height=h; radius=r
        // Tiny targets and almost-unmasked rectangles are cheaper as the original draw.
        // Estimate discarded area in eight horizontal strips only when the footprint changes.
        var inside=0.0
        for(i in 0 until 8) {
            val y=(i+.5)*h/16
            inside+=min(1.0, 2*sqrt(max(0.0,r*r-y*y))/w)
        }
        cropLayers = w.toDouble()*h*s.settings.layerScale*s.settings.layerScale>=400_000 &&
            inside/8 < .92
        if (!cropLayers) return
        val circumscribed=r/cos(PI/sides)
        vertices.clear()
        for(i in 0 until sides) {
            val a=2*PI*i/sides
            vertices.put((2*circumscribed*cos(a)/w).toFloat())
            vertices.put((2*circumscribed*sin(a)/h).toFloat())
        }
        vertices.flip()
        val gl=Gdx.gl30
        if(buffer==0)buffer=gl.glGenBuffer()
        gl.glBindBuffer(GL_ARRAY_BUFFER,buffer)
        gl.glBufferData(GL_ARRAY_BUFFER,vertices.remaining()*4,vertices,GL_DYNAMIC_DRAW)
    }

    /** Bounds already include interpolation, cell radii and the rim allowance, in world units. */
    fun overlapsWorld(left:Float,bottom:Float,right:Float,top:Float,state:OpticalState):Boolean {
        if(!enabled) return true
        val dx=maxOf(left-state.camX, state.camX-right, 0.0)
        val dy=maxOf(bottom-state.camY, state.camY-top, 0.0)
        val reach=radius*state.scale
        return dx*dx + dy*dy <= reach*reach
    }

    fun bindRadius(p: OpticalProgram) {
        if (enabled) p.v2("uEyeRadius", (radius/width).toFloat(), (radius/height).toFloat())
        else p.v2("uEyeRadius", -1f, -1f)
    }

    /** For tissue geometry and external sprites the vertices belong to the object. */
    fun scissor(w:Int,h:Int,other:IntArray?=null) {
        val halfW=ceil(radius*w/width+.5).toInt()
        val halfH=ceil(radius*h/height+.5).toInt()
        val left=max(other?.get(0) ?: 0,w/2-halfW)
        val bottom=max(other?.get(1) ?: 0,h/2-halfH)
        val right=min(if(other==null)w else other[0]+other[2],(w+1)/2+halfW)
        val top=min(if(other==null)h else other[1]+other[3],(h+1)/2+halfH)
        Gdx.gl.glEnable(GL_SCISSOR_TEST)
        Gdx.gl.glScissor(left,bottom,max(0,right-left),max(0,top-bottom))
    }

    /** Same fullscreen vertex shader (position at location zero), same gl_FragCoord. */
    fun draw() {
        val gl=Gdx.gl30
        gl.glDrawArrays(GL_TRIANGLE_FAN,0,sides)
    }

    override fun dispose() { if(buffer!=0)Gdx.gl.glDeleteBuffer(buffer) }
}
