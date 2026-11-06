package io.github.some_example_name.render.microscope

import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL30.*
import kotlin.math.ceil

/** Удаляет окружение из графа, а не только делает его прозрачным.
 * Нейтральный фон — один RGBA16F-тексель, обновляется только при смене режима. */
internal class OpticalEnvironment(commands:List<OpticalPass>,sources:(Int,Boolean)->String):Disposable {
    val atlas=commands.first{it.stage=="atlas"&&it.op=="draw"}.target
    val background=commands.first{it.stage=="volume-background"&&it.op=="draw"}.target
    private val final=commands.last{it.stage=="post"&&it.op=="draw"}
    private val lensCommand=commands.first{it.stage=="post"&&it.op=="draw"&&it.uniforms.any{u->u.name=="uHair"}}
    private val slide=LiveSlideBackground(sources(lensCommand.program,false),lensCommand.uniforms.first{it.name=="uHair"}.source)
    val spriteSource=commands.first{it.stage=="atlas"&&it.op=="draw"}.units.first()[1]
    val dirtTexture get()=slide.dirtTexture
    private val blank=OpticalTarget(1,1,true)
    private val emptyOptics=OpticalTarget(1,1,true)
    private var emptyImage:OpticalTarget?=null
    private var emptyKey=-1
    private var emptyCopy:OpticalProgram?=null
    private var emptyDraw:GpuOpticalDraw?=null
    private var key=-1
    val texture get()=if(slide.background!=0)slide.background else blank.texture
    val post:OpticalProgram
    init {
        val original=sources(final.program,false)
        val start=original.indexOf("  vec3 glow=")
        val end=original.indexOf("  col*=uExpo;",start)
        check(start>=0&&end>start){"Post shader bloom block changed"}
        // Outside the ocular field only the sensor floor, its tint and noise survive.
        // Skip slide/dirt reads there, preserving the original camera math in both paths.
        val base=original.removeRange(start,end)
        val coordinate="vec2 s=(gl_FragCoord.xy-.5*uRes)/uRes.y; float rr=length(s);"
        val edge="float edge=.98-.26*uVig;"
        val fieldMix="col=mix(vec3(.012,.011,.01),col,field);"
        check(coordinate in base && edge in base && fieldMix in base) { "Eyepiece post shader changed" }
        val source=base
            .replace(coordinate,"").replace(edge,"")
            .replace("vec4 c0=texture(uCol,uv);","""
                $coordinate
                $edge
                vec3 col=vec3(.012,.011,.01);
                if(rr<edge+.006 || uMask>.5){
                    vec4 c0=texture(uCol,uv);
            """.trimIndent())
            .replace("vec3 col=c0.rgb;","col=c0.rgb;")
            .replace(fieldMix,"$fieldMix\n}")
        post=OpticalProgram(sources(final.program,true),source,true)
    }
    fun emptyTexture(state:OpticalState):Int {
        if(slide.background!=0)return slide.background
        val w=ceil(state.width*state.settings.layerScale).toInt().coerceAtLeast(1)
        val h=ceil(state.height*state.settings.layerScale).toInt().coerceAtLeast(1)
        if(emptyImage?.width!=w||emptyImage?.height!=h){
            emptyImage?.dispose();emptyImage=OpticalTarget(w,h);emptyKey=-1
        }
        if(emptyKey!=key){
            // total-optics сначала округляет в RGBA8 с GL_DITHER. Один float-тексель
            // вместо этой карты менял результат post на 1/255. Запекаем тот же
            // сигнал один раз при смене размера/режима (7.91 МиБ при 1920x1080).
            if(emptyCopy==null){
                emptyCopy=OpticalProgram("""#version 300 es
                    precision highp float;
                    void main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0.,1.);}
                """.trimIndent(),"""#version 300 es
                    precision highp float;
                    uniform sampler2D uEmpty;
                    layout(location=0) out vec4 outColor;
                    void main(){outColor=texture(uEmpty,vec2(.5));}
                """.trimIndent(),true)
                emptyDraw=GpuOpticalDraw()
            }
            val gl=Gdx.gl30;gl.glDisable(GL_SCISSOR_TEST);gl.glDisable(GL_DEPTH_TEST);gl.glDisable(GL_BLEND);gl.glDisable(GL_CULL_FACE)
            gl.glColorMask(true,true,true,true);gl.glEnable(GL_DITHER)
            emptyImage!!.bind();emptyCopy!!.use();emptyCopy!!.texture("uEmpty",emptyOptics.texture,0);emptyDraw!!.full()
            gl.glBindVertexArray(0);emptyKey=key
        }
        return emptyImage!!.texture
    }
    fun prepare(state:OpticalState,sprites:Int,eyepiece:EyepieceFootprint){
        slide.prepare(state,sprites,eyepiece)
        val s=state.settings
        val next=s.opticalView*2+if(s.darkField)1 else 0
        if(next==key)return
        key=next
        when {
            s.opticalView==2->blank.clear(1f,1f,1f,1f)
            s.opticalView!=0->blank.clear(0f,0f,0f,1f)
            s.darkField->blank.clear(.012f,.012f,.012f,1f)
            else->blank.clear(.69f,.695f,.705f,1f)
        }
        // Точное значение total-optics при нулевой OPD/поглощении. Глубина пустоты
        // в диагностике равна .5, а не нулю фонового прохода. Формат тот же RGBA16F.
        when {
            s.opticalView==2->emptyOptics.clear(1f,1f,1f,.5f)
            s.opticalView==3->emptyOptics.clear(.5f,.5f,.5f,.5f)
            s.opticalView!=0->emptyOptics.clear(0f,0f,0f,.5f)
            s.darkField->emptyOptics.clear(.012f,.012f,.012f,.5f)
            else->emptyOptics.clear(.69f,.695f,.705f,.5f)
        }
    }
    fun skip(c:OpticalPass,dirt:Int)=c.target==dirt||when(c.stage){
        "atlas","environment","material","halo","environment-optics","environment-dof"->true
        // В HTML эта стадия также очищает карты самой ткани и накопление окраски.
        "volume-background"->c.op!="clear"
        else->c.op=="mipmap"&&c.texture==atlas
    }
    fun program(c:OpticalPass)=if(c.program==final.program)post else null
    override fun dispose(){slide.dispose();post.dispose();blank.dispose();emptyOptics.dispose();emptyImage?.dispose();emptyCopy?.dispose();emptyDraw?.dispose()}
}
