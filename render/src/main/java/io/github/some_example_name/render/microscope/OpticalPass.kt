package io.github.some_example_name.render.microscope

import com.badlogic.gdx.utils.JsonValue

/** Описание прохода разбирается один раз при создании игрового конвейера. */
internal class OpticalPass(node:JsonValue) {
    val op=node.getString("op")
    val stage=node.getString("stage","")
    val target=node.getInt("target",-1)
    val texture=node.getInt("texture",-1)
    val program=node.getInt("program",-1)
    val slice=node.getInt("slice",-1)
    val viewport=node.get("viewport")?.asIntArray()?:intArrayOf(0,0,1,1)
    val scissor=node.get("scissor")?.takeUnless{it.isNull}?.asIntArray()
    val blend=node.get("blend")?.takeUnless{it.isNull}?.asIntArray()
    val depth=node.get("depth")?.takeIf{it.isBoolean}?.asBoolean()?:false
    val clearDepth=if(op=="clear")node.getFloat("depth") else 1f
    val color=node.get("color")?.asFloatArray()?:floatArrayOf(0f,0f,0f,0f)
    val mask=node.getInt("mask",0)
    val mode=node.getInt("mode",0)
    val first=node.getInt("first",0)
    val count=node.getInt("count",0)
    val instances=node.getInt("instances",1)
    val uniforms=node.get("uniforms")?.map{Uniform(it)}?:emptyList()
    val units=node.get("units")?.map{intArrayOf(it.name.toInt(),it.asInt())}?:emptyList()
    val attrs=node.get("attrs")?.map{a->intArrayOf(a.getInt("index"),a.getInt("buffer"),a.getInt("size"),a.getInt("type"),a.getInt("stride"),a.getInt("offset"),a.getInt("divisor"))}?:emptyList()
    class Uniform(node:JsonValue){
        val name=node.getString("name")
        val type=node.getInt("type")
        val source=node.get("value").asFloatArray()
        val value=source.copyOf()
    }
}
