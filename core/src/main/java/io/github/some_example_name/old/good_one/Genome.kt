package io.github.some_example_name.old.good_one

import com.badlogic.gdx.graphics.Color
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import io.github.some_example_name.old.logic.CellManager


//TODO возможно зацикленность и регенерацию на такой системе не построишь
class GenomeiaGenome(
    val genomeStageInstruction: ArrayList<GenomeStage> = arrayListOf()
)

//val genomeStageInstruction = ArrayList<GenomeStage>()
val genomeStageInstruction = readGenome("gen2.bin").genomeStageInstruction//= ArrayList<GenomeStage>()

class GenomeStage(
    val cellActions: HashMap<String, CellAction> = hashMapOf()
)

class CellAction(
    var divide: Action? = null,
    var mutate: Action? = null
)

class Action(
    val id: String? = "",
    var angle: Float? = 0f,
    val cellType: Int? = 0,
    val indexOfNew: Int = -1,
    val physicalLink: HashMap<Int, LinkData?> = hashMapOf(),
    val color: Color? = null,
    val angleDirected: Float? = null,
    val startDirectionId: Int? = null,
    val funActivation: Int? = null,
    val a: Float? = null,
    val b: Float? = null,
    val c: Float? = null,
)

class LinkData(
    val length: Float = 10f,
    val isNeuronal: Boolean = false,
    val weight: Float? = null,
    val directedNeuronLink: Int? = null
)


private fun getJarDir(): File {
    try {
        val path: String = CellManager::class.java.getProtectionDomain().getCodeSource().getLocation()
                .toURI().getPath()
        val jarFile = File(path)
        return if (jarFile.isDirectory) jarFile else jarFile.parentFile
    } catch (e: Exception) {
        return File(System.getProperty("user.dir"))
    }
}

fun writeGenome(genomeStageInstruction: ArrayList<GenomeStage>, relativeFileName: String) {
    val baseDir = getJarDir()
    val file = File(baseDir, relativeFileName).absoluteFile

    file.parentFile?.mkdirs()

    val kryo = buildKryo()
    val output = Output(FileOutputStream(file))
    kryo.writeObject(output, GenomeiaGenome(genomeStageInstruction))
    output.close()

    println("Genome written to ${file.absolutePath}")
}

fun readGenome(relativeFileName: String): GenomeiaGenome {
    val baseDir = getJarDir()
    val file = File(baseDir, relativeFileName).absoluteFile

    return if (file.exists()) {
        val kryo = buildKryo()
        val input = Input(FileInputStream(file))
        val deserializedData: GenomeiaGenome = kryo.readObject(input, GenomeiaGenome::class.java)
        input.close()
        println("Deserialized: $deserializedData")
        deserializedData
    } else {
        println("File not found, returning new GenomeiaGenome with empty stages")
        GenomeiaGenome(ArrayList())
    }
}

private fun buildKryo() = Kryo().also { kryo ->
    kryo.register(Color::class.java)
    kryo.register(HashSet::class.java)
    kryo.register(GenomeiaGenome::class.java)
    kryo.register(CellAction::class.java)
    kryo.register(Action::class.java)
    kryo.register(GenomeStage::class.java)
    kryo.register(HashMap::class.java)
    kryo.register(ArrayList::class.java)
    kryo.register(LinkData::class.java)
}

