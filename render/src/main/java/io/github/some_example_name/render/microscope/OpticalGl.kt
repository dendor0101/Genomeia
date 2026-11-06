package io.github.some_example_name.render.microscope

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Application.ApplicationType
import com.badlogic.gdx.graphics.GL20.*
import com.badlogic.gdx.graphics.GL30.*
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.Disposable

internal class OpticalProgram(vertex:String, fragment:String, source:Boolean=false):Disposable {
    val shader:ShaderProgram
    init {
        fun read(s:String)=if(source)s else Gdx.files.internal("shaders/microscope/$s").readString("UTF-8")
        fun convert(s:String)=if(Gdx.app.type==ApplicationType.Desktop) s.replace("#version 300 es","#version 330 core") else s
        val vs=OpticalShaderSource.attributes(read(vertex))
        shader=ShaderProgram(convert(vs),convert(read(fragment)))
        check(shader.isCompiled){"Microscope shader $fragment: ${shader.log}"}
    }
    fun use(){shader.bind()}
    fun f(n:String,v:Float){val l=shader.fetchUniformLocation(n,false);if(l>=0)Gdx.gl.glUniform1f(l,v)}
    fun i(n:String,v:Int){val l=shader.fetchUniformLocation(n,false);if(l>=0)Gdx.gl.glUniform1i(l,v)}
    fun v2(n:String,a:Float,b:Float){val l=shader.fetchUniformLocation(n,false);if(l>=0)Gdx.gl.glUniform2f(l,a,b)}
    fun v3(n:String,a:Float,b:Float,c:Float){val l=shader.fetchUniformLocation(n,false);if(l>=0)Gdx.gl.glUniform3f(l,a,b,c)}
    fun v4(n:String,a:Float,b:Float,c:Float,d:Float){val l=shader.fetchUniformLocation(n,false);if(l>=0)Gdx.gl.glUniform4f(l,a,b,c,d)}
    fun array4(n:String,v:FloatArray){val l=shader.fetchUniformLocation(n,false);if(l>=0)Gdx.gl.glUniform4fv(l,v.size/4,v,0)}
    fun texture(n:String,id:Int,slot:Int,target:Int=GL_TEXTURE_2D){
        val l=shader.fetchUniformLocation(n,false);if(l<0)return
        Gdx.gl.glActiveTexture(GL_TEXTURE0+slot);Gdx.gl.glBindTexture(target,id);Gdx.gl.glUniform1i(l,slot)
    }
    override fun dispose(){shader.dispose()}
}

internal class OpticalTarget(val width:Int,val height:Int, floating:Boolean=false, depth:Boolean=false,val attachments:Int=1,
                           internalFormat:Int=if(floating)GL_RGBA16F else GL_RGBA8,attachmentFormats:IntArray?=null):Disposable {
    val textures=IntArray(attachments);val framebuffer=Gdx.gl.glGenFramebuffer();private var depthBuffer=0
    init {
        val gl=Gdx.gl30;gl.glBindFramebuffer(GL_FRAMEBUFFER,framebuffer)
        for(i in textures.indices) {
            textures[i]=gl.glGenTexture();gl.glBindTexture(GL_TEXTURE_2D,textures[i])
            val attachmentFormat=attachmentFormats?.get(i)?:internalFormat
            val format=when(attachmentFormat){GL_R32F,GL_R16F->GL_RED;GL_RG32F->GL_RG;else->GL_RGBA}
            val type=when(attachmentFormat){GL_R32F,GL_RG32F,GL_RGBA32F->GL_FLOAT;GL_RGBA16F,GL_R16F->GL_HALF_FLOAT;else->GL_UNSIGNED_BYTE}
            gl.glTexImage2D(GL_TEXTURE_2D,0,attachmentFormat,width,height,0,format,type,null)
            val filter=if(type==GL_FLOAT)GL_NEAREST else GL_LINEAR
            gl.glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,filter);gl.glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,filter)
            gl.glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);gl.glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE)
            gl.glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0+i,GL_TEXTURE_2D,textures[i],0)
        }
        val draws=BufferUtils.newIntBuffer(attachments);for(i in textures.indices)draws.put(GL_COLOR_ATTACHMENT0+i);draws.flip();gl.glDrawBuffers(attachments,draws)
        if(depth) {depthBuffer=gl.glGenRenderbuffer();gl.glBindRenderbuffer(GL_RENDERBUFFER,depthBuffer);gl.glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH_COMPONENT24,width,height);gl.glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_RENDERBUFFER,depthBuffer)}
        val status=gl.glCheckFramebufferStatus(GL_FRAMEBUFFER)
        check(status==GL_FRAMEBUFFER_COMPLETE){
            val formats=textures.indices.joinToString { "0x"+(attachmentFormats?.get(it)?:internalFormat).toString(16) }
            "Microscope FBO incomplete: 0x${status.toString(16)}, ${width}x${height}, color formats=[$formats], depth=$depth"
        }
        gl.glBindFramebuffer(GL_FRAMEBUFFER,0)
    }
    val texture get()=textures[0]
    fun bind(){Gdx.gl.glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);Gdx.gl.glViewport(0,0,width,height)}
    fun clear(r:Float=0f,g:Float=0f,b:Float=0f,a:Float=0f){bind();Gdx.gl.glClearColor(r,g,b,a);Gdx.gl.glClear(GL_COLOR_BUFFER_BIT or if(depthBuffer!=0)GL_DEPTH_BUFFER_BIT else 0)}
    override fun dispose(){for(t in textures)Gdx.gl.glDeleteTexture(t);Gdx.gl.glDeleteFramebuffer(framebuffer);if(depthBuffer!=0)Gdx.gl.glDeleteRenderbuffer(depthBuffer)}
}
