package io.github.some_example_name.old.core

import com.badlogic.gdx.utils.Json
import com.badlogic.gdx.utils.JsonWriter

val json = Json().apply { setOutputType(JsonWriter.OutputType.json) }

fun <T> prettyPrint(objectToPrint: T) {
    println(json.prettyPrint(objectToPrint, 120))
}

fun <T> printObjectMemoryAddress(objectToPrint: T) {
    println("${objectToPrint?.javaClass?.name}@${Integer.toHexString(System.identityHashCode(objectToPrint))}")
}
