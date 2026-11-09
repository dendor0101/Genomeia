package io.github.some_example_name.render.microscope

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL30.*
import com.badlogic.gdx.utils.Disposable

/** Геометрия из gl_VertexID: нет CPU-вершин, сетки и отдельных VBO на организм. */
internal class GpuOpticalDraw : Disposable {
    private val vao=IntArray(1)
    init { Gdx.gl30.glGenVertexArrays(1,vao,0) }
    fun bind(){Gdx.gl30.glBindVertexArray(vao[0])}
    fun full(){val gl=Gdx.gl30;gl.glBindVertexArray(vao[0]);gl.glDrawArrays(GL_TRIANGLES,0,3)}
    fun cells(count:Int){val gl=Gdx.gl30;gl.glBindVertexArray(vao[0]);gl.glDrawArraysInstanced(GL_TRIANGLES,0,6,count)}
    fun points(count:Int){val gl=Gdx.gl30;gl.glBindVertexArray(vao[0]);gl.glDrawArrays(GL_POINTS,0,count)}
    override fun dispose(){Gdx.gl30.glDeleteVertexArrays(1,vao,0)}
}
