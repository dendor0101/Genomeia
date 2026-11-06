package io.github.some_example_name.render.microscope

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL30.*
import com.badlogic.gdx.utils.Disposable

/** Три плоскости взвеси за один проход в четверть ширины и высоты экрана.
 * Пыль объектива имеет собственную карту, которая строится один раз на размер. */
internal class LiveSlideBackground(private val dirtFragment:String,private val hair:FloatArray):Disposable {
    private val draw=GpuOpticalDraw()
    private var particles:OpticalProgram?=null
    private var lens:OpticalProgram?=null
    private var field:OpticalTarget?=null
    private var dust:OpticalTarget?=null
    private var mipSource=0
    private val flow=FloatArray(2)
    var background=0;private set
    var dirtTexture=0;private set

    private fun begin(target:OpticalTarget){
        val gl=Gdx.gl30
        gl.glDisable(GL_BLEND);gl.glDisable(GL_DEPTH_TEST);gl.glDisable(GL_SCISSOR_TEST);gl.glDisable(GL_CULL_FACE)
        gl.glColorMask(true,true,true,true);target.bind()
    }
    fun prepare(state:OpticalState,source:Int,eyepiece:EyepieceFootprint){
        val w=(state.width+3)/4;val h=(state.height+3)/4
        dirtTexture=0;background=0
        if(state.settings.dirt){
            if(dust?.width!=state.width||dust?.height!=state.height){
                dust?.dispose();dust=OpticalTarget(state.width,state.height)
                val program=lens?:OpticalProgram(vertex,dirtFragment,true).also{lens=it}
                begin(dust!!);program.use();program.v2("uRes",state.width.toFloat(),state.height.toFloat())
                program.v4("uHair",hair[0],hair[1],hair[2],hair[3]);draw.full()
            }
            dirtTexture=dust!!.texture
        }
        if(state.settings.noEnvironment||state.settings.debris<=0f||state.settings.opticalView!=0)return
        if(field?.width!=w||field?.height!=h){field?.dispose();field=OpticalTarget(w,h)}
        val program=particles?:OpticalProgram(vertex,fragment,true).also{particles=it}

        if(source!=mipSource){
            val gl=Gdx.gl30;gl.glBindTexture(GL_TEXTURE_2D,source)
            gl.glGenerateMipmap(GL_TEXTURE_2D)
            gl.glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR_MIPMAP_LINEAR)
            mipSource=source
        }
        begin(field!!);program.use();program.texture("uSprites",source,0)
        program.v2("uSpan",(state.scale*state.width).toFloat(),(state.scale*state.height).toFloat())
        program.v2("uCam",state.camX.toFloat(),state.camY.toFloat())
        program.v2("uPar",state.parallax.x,state.parallax.y)
        state.uniform("uFlow",flow,"suspension",w,h);program.v2("uFlow",flow[0],flow[1])
        program.f("uTime",state.time.toFloat());program.f("uDensity",state.settings.debris)
        program.f("uDark",if(state.settings.darkField)1f else 0f)
        if(eyepiece.enabled)eyepiece.scissor(w,h)
        draw.full()
        if(eyepiece.enabled)Gdx.gl.glDisable(GL_SCISSOR_TEST)
        background=field!!.texture;
    }
    override fun dispose(){field?.dispose();dust?.dispose();lens?.dispose();particles?.dispose();draw.dispose()}
    companion object{
        private const val vertex="""#version 300 es
precision highp float;
out vec2 uv;
void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=p;gl_Position=vec4(p*2.-1.,0.,1.);}
"""
        private const val fragment="""#version 300 es
precision highp float;
in vec2 uv;out vec4 color;
uniform sampler2D uSprites;
uniform vec2 uSpan,uCam,uPar,uFlow;
uniform float uTime,uDensity,uDark;
vec3 random3(vec2 id){vec3 p=fract(id.xyx*vec3(.1031,.1030,.0973));p+=dot(p,p.yxz+33.33);return fract((p.xxy+p.yzz)*p.zyx);}
vec3 plane(float z,float spacing,float seed,float lod){
    vec2 world=uCam+(uv-.5)*uSpan+uPar*(.5-z)*1.6+uFlow*(1.-abs(z-.5));
    vec2 grid=world/spacing;vec3 r=random3(floor(grid)+seed);
    if(r.x>min(1.,uDensity))return vec3(0.);
    float phase=6.28*r.z;
    vec2 q=(fract(grid)-(.30+.40*r.yz+.025*vec2(sin(phase+uTime*.53),cos(phase+uTime*.47))))/(.10+.08*r.y);
    float a=phase+(r.z-.5)*uTime*.16;float c=cos(a),s=sin(a);
    q=mat2(c,s,-s,c)*q;
    if(max(abs(q.x),abs(q.y))>=1.)return vec3(0.);
    float tile=floor(random3(floor(grid)+seed+17.).x*16.);
    vec2 st=(vec2(mod(tile,4.),floor(tile/4.))+.5+q*.46)*.25;
    vec4 sampleColor=textureLod(uSprites,st,lod);
    return (vec3(1.)-sampleColor.rgb)*sampleColor.a;
}
void main(){
    // При сохранённой плотности .16: ~1.02 центра на единицу площади (раньше ~2.19).
    vec3 d=plane(.25,1.60,11.,3.)*.27+plane(.51,.47,43.,1.)*.44+plane(.78,.83,71.,4.)*.31;
    color=vec4(mix(vec3(.69,.695,.705)*(1.-d),vec3(.012,.012,.012)+d*.48,uDark),.5);
}
"""
    }
}
