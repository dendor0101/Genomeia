package io.github.some_example_name.attempts.game.gameabstraction.entity


import com.badlogic.gdx.graphics.Color
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import java.io.FileInputStream
import java.io.FileOutputStream
import io.github.some_example_name.attempts.utils.Pair


sealed interface Gen

data class Cycle(val genomeId: String) : Gen

data class GenomeLeaf(
    val id: Int? = null,
    val angleCellDivision: Float = 40f/*(0..360).random().toFloat()*/,
    val joins: List<Int> = listOf(),
    val joinLength: Float = 10f,
    val heirPair: Pair<Gen, Gen>? = null,
    val color: Color = Color.WHITE
) : Gen

//data class Color(val r = 1f: Float, val g: Float, val b: Float)

//fun Color.toGdxColor() = com.badlogic.gdx.graphics.Color(r, g, b, 1f)
//fun com.badlogic.gdx.graphics.Color.toCustomColor() = Color(r, g, b)

/*
val genome2 = hashMapOf(
    "plug" to GenomeLeaf(
        angleCellDivision = 45f,
        joinLength = 1f,
        id = 0,
        color = Color.PINK,
        heirPair = null
    ),
    "tree" to GenomeLeaf(
        angleCellDivision = 45f,
        joinLength = 1f,
        id = 0,
        color = Color.RED,
        heirPair = Pair(
            GenomeLeaf(
                angleCellDivision = 135f,
                joinLength = 1f,
                id = 1,
                color = Color.BROWN,
                heirPair = Pair(
                    GenomeLeaf(
                        angleCellDivision = 0f,
                        joinLength = 1f,
                        id = 3,
                        color = Color.GRAY,
                        heirPair = null
                    ), GenomeLeaf(
                        angleCellDivision = 0f,
                        joinLength = 1f,
                        id = 4,
                        color = Color.GRAY,
                        heirPair = null
                    )
                )
            ),
            GenomeLeaf(
                angleCellDivision = 135f,
                joinLength = 1f,
                id = 2,
                color = Color.BROWN,
                heirPair = Pair(
                    GenomeLeaf(
                        angleCellDivision = 0f,
                        joinLength = 1f,
                        id = 5,
                        color = Color.GRAY,
                        heirPair = null
                    ), GenomeLeaf(
                        angleCellDivision = 0f,
                        joinLength = 1f,
                        id = 6,
                        color = Color.GRAY,
                        heirPair = null
                    )
                )
            )
        )
    )
)
*/
val genome by lazy {
    hashMapOf(
//        "file" to readGenome("C:\\game\\gen123.bin"),
        "plug" to GenomeLeaf(
            angleCellDivision = 45f,
            joinLength = 1f,
            id = 0,
            heirPair = null
        ),
        "tree" to GenomeLeaf(
            angleCellDivision = 45f,
            joinLength = 1f,
            id = 0,
            heirPair = Pair(
                GenomeLeaf(
                    angleCellDivision = 135f,
                    joinLength = 1f,
                    id = 1,
                    heirPair = Pair(
                        GenomeLeaf(
                            angleCellDivision = 0f,
                            joinLength = 1f,
                            id = 3,
                            heirPair = Pair(
                                GenomeLeaf(
                                    angleCellDivision = 45f,
                                    joinLength = 1f,
                                    id = 7,
                                    heirPair = Pair(
                                        Cycle("plug"),
                                        Cycle("plug")
                                    )
                                ),
                                GenomeLeaf(
                                    angleCellDivision = 45f,
                                    joinLength = 1f,
                                    id = 8,
                                    heirPair = Pair(
                                        Cycle("plug"),
                                        Cycle("plug")
                                    )
                                )
                            )
                        ),
                        GenomeLeaf(
                            angleCellDivision = 0f,
                            joinLength = 1f,
                            id = 4,
                            heirPair = Pair(
                                GenomeLeaf(
                                    angleCellDivision = 45f,
                                    joinLength = 1f,
                                    id = 9,
                                    heirPair = Pair(
                                        Cycle("plug"),
                                        Cycle("plug")
                                    )
                                ),
                                GenomeLeaf(
                                    angleCellDivision = 45f,
                                    joinLength = 1f,
                                    id = 10,
                                    heirPair = Pair(
                                        Cycle("plug"),
                                        Cycle("plug")
                                    )
                                )
                            )
                        )
                    )
                ),
                GenomeLeaf(
                    angleCellDivision = 135f,
                    joinLength = 1f,
                    id = 2,
                    heirPair = Pair(
                        GenomeLeaf(
                            angleCellDivision = 0f,
                            joinLength = 1f,
                            id = 5,
                            heirPair = Pair(
                                GenomeLeaf(
                                    angleCellDivision = 45f,
                                    joinLength = 1f,
                                    id = 11,
                                    heirPair = Pair(
                                        Cycle("plug"),
                                        Cycle("plug")
                                    )
                                ),
                                GenomeLeaf(
                                    angleCellDivision = 45f,
                                    joinLength = 1f,
                                    id = 12,
                                    heirPair = Pair(
                                        Cycle("plug"),
                                        Cycle("plug")
                                    )
                                )
                            )
                        ),
                        GenomeLeaf(
                            angleCellDivision = 0f,
                            joinLength = 1f,
                            id = 6,
                            heirPair = Pair(
                                GenomeLeaf(
                                    angleCellDivision = 45f,
                                    joinLength = 1f,
                                    id = 13,
                                    heirPair = Pair(
                                        Cycle("plug"),
                                        Cycle("plug")
                                    )
                                ),
                                GenomeLeaf(
                                    angleCellDivision = 45f,
                                    joinLength = 1f,
                                    id = 14,
                                    heirPair = Pair(
                                        Cycle("plug"),
                                        Cycle("tree")
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
    )
}

data class GenomeExample(
    val angle: Float = 0f
)

fun traverseGenome(root: Gen?, path: List<Boolean>): GenomeLeaf? {
    var current: GenomeLeaf? = root as? GenomeLeaf ?: return null

    for (direction in path) {
        if (current !is GenomeLeaf || current.heirPair == null) return null // Если наткнулись на Cycle или null, завершаем

        current = (if (direction) current.heirPair!!.second else current.heirPair!!.first) as? GenomeLeaf
    }

    return current
}

//fun writeGenome(leafs: GenomeLeaf, s: String) {
//    val kryo = Kryo()
//    kryo.register(GenomeLeaf::class.java)
//    kryo.register(Color::class.java)
//    kryo.register(Pair::class.java)
//    kryo.register(ArrayList::class.java)
//
//    // Сериализация
//    val output = Output(FileOutputStream(s))
//    kryo.writeObject(output, leafs)
//    output.close()
//}

fun writeGenome(genome: GenomeLeaf, filePath: String) {
    val kryo = Kryo()
    kryo.register(GenomeLeaf::class.java)
    kryo.register(Color::class.java)
    kryo.register(Pair::class.java)
    kryo.register(ArrayList::class.java)

    // Сериализация
    val output = Output(FileOutputStream(filePath))
    kryo.writeObject(output, genome)
    output.close()

    println("Genome written to $filePath")
}

fun readGenome(s: String): GenomeLeaf {
    val kryo = Kryo()
    kryo.register(GenomeLeaf::class.java)
    kryo.register(Color::class.java)
    kryo.register(Pair::class.java)
    kryo.register(ArrayList::class.java)

    // Десериализация
    val input = Input(FileInputStream(s))
    val deserializedData: GenomeLeaf = kryo.readObject(input, GenomeLeaf::class.java)
    input.close()

    println("Deserialized: $deserializedData")
    return deserializedData
}


fun main() {
    readGenome("C:\\game\\gen123.bin")
}
