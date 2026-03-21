package io.github.some_example_name.old.core

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.utils.Json
import com.badlogic.gdx.utils.JsonWriter
import io.github.some_example_name.old.systems.genomics.genome.GenomeJsonReader
import java.io.File

data class GlobalSimulationSettings(
    val amountOfSolarEnergy: Float = 0.06f,
    val viscosityOfTheEnvironment: Float = 0.03f,
    val tailMaxSpeedCoefficient: Float = 0.025f,
    val producerRestoreTimeTickCoefficient: Float = 4f,
    val amountOfFoodEnergy: Float = 4f,
    val rateOfEnergyTransferInLinks: Float = 0.03f,
    val rateOfEnergyTransferForPumper: Float = 0.03f,
    val rateOfPheromoneDiffusion: Float = 4.0e-3f,
    val rateOfPheromoneDegradation: Float = 1.6e-4f,
    val theNumberOfTicksHungryCellDies: Int = 200,
    val gravity: Float = 0.0f,
    val linkMaxLength: Float = 3f,
    val cellsSettings: Map<String, CellSettings> = defaultCellSettingsMap()
)

fun defaultCellSettingsMap() = mapOf(
    "Leaf" to CellSettings(
        maxEnergy = 5f,
        cellStiffness = 0.02f,
        linkStiffness = 0.025f,
        energyActionCost = 0f
    ),
    "Fat" to CellSettings(
        maxEnergy = 10f,
        cellStiffness = 0.01f,
        linkStiffness = 0.0125f,
        energyActionCost = 0f
    ),
    "Bone" to CellSettings(
        maxEnergy = 3f,
        cellStiffness = 0.04f,
        linkStiffness = 0.4f,
        energyActionCost = 0f
    ),
    "Tail" to CellSettings(
        maxEnergy = 5f,
        cellStiffness = 0.02f,
        linkStiffness = 0.025f,
        energyActionCost = 5.0E-4f
    ),
    "Neuron" to CellSettings(
        maxEnergy = 5f,
        cellStiffness = 0.02f,
        linkStiffness = 0.025f,
        energyActionCost = 3.0E-4f
    ),
    "Muscle" to CellSettings(
        maxEnergy = 5f,
        cellStiffness = 0.02f,
        linkStiffness = 0.025f,
        energyActionCost = 5.0E-4f
    ),
    "Sensor" to CellSettings(
        maxEnergy = 5f,
        cellStiffness = 0.02f,
        linkStiffness = 0.025f,
        energyActionCost = 3.0E-4f
    ),
    "Sucker" to CellSettings(
        maxEnergy = 8f,
        cellStiffness = 0.02f,
        linkStiffness = 0.025f,
        energyActionCost = 0f
    ),
    "AccelerationSensor" to CellSettings(
        maxEnergy = 5f,
        cellStiffness = 0.02f,
        linkStiffness = 0.025f,
        energyActionCost = 1.0E-4f
    ),
    "Excreta" to CellSettings(
        maxEnergy = 5f,
        cellStiffness = 0.02f,
        linkStiffness = 0.025f,
        energyActionCost = 0f
    ),
    "SkinCell" to CellSettings(
        maxEnergy = 5f,
        cellStiffness = 0.02f,
        linkStiffness = 0.025f,
        energyActionCost = 1.0E-4f
    ),
    "Sticky" to CellSettings(
        maxEnergy = 5f,
        cellStiffness = 0.02f,
        linkStiffness = 0.025f,
        energyActionCost = 0f
    ),
    "Pumper" to CellSettings(
        maxEnergy = 5f,
        cellStiffness = 0.02f,
        linkStiffness = 0.025f,
        energyActionCost = 0f
    ),
    "Chameleon" to CellSettings(
        maxEnergy = 5f,
        cellStiffness = 0.02f,
        linkStiffness = 0.025f,
        energyActionCost = 1.0E-4f
    ),
    "Eye" to CellSettings(
        maxEnergy = 5f,
        cellStiffness = 0.02f,
        linkStiffness = 0.025f,
        energyActionCost = 5.0E-4f
    ),
    "Compass" to CellSettings(
        maxEnergy = 5f,
        cellStiffness = 0.02f,
        linkStiffness = 0.025f,
        energyActionCost = 3.0E-4f
    ),
    "Controller" to CellSettings(
        maxEnergy = 5f,
        cellStiffness = 0.02f,
        linkStiffness = 0.025f,
        energyActionCost = 1.0E-4f
    ),
    "TouchTrigger" to CellSettings(
        maxEnergy = 5f,
        cellStiffness = 0.02f,
        linkStiffness = 0.025f,
        energyActionCost = 3.0E-4f
    ),
    "Zygote" to CellSettings(
        maxEnergy = 10f,
        cellStiffness = 0.02f,
        linkStiffness = 0.0125f,
        energyActionCost = 0f
    ),
    "Producer" to CellSettings(
        maxEnergy = 10f,
        cellStiffness = 0.02f,
        linkStiffness = 0.0125f,
        energyActionCost = 0f
    ),
    "Breakaway" to CellSettings(
        maxEnergy = 3f,
        cellStiffness = 0.02f,
        linkStiffness = 0.025f,
        energyActionCost = 3.0E-4f
    ),
    "Vascular" to CellSettings(
        maxEnergy = 3f,
        cellStiffness = 0.02f,
        linkStiffness = 0.0125f,
        energyActionCost = 0f
    ),
    "PheromoneEmitter" to CellSettings(
        maxEnergy = 5f,
        cellStiffness = 0.02f,
        linkStiffness = 0.0125f,
        energyActionCost = 0f
    ),
    "PheromoneSensor" to CellSettings(
        maxEnergy = 5f,
        cellStiffness = 0.02f,
        linkStiffness = 0.025f,
        energyActionCost = 0f
    ),
    "Punisher" to CellSettings(
        maxEnergy = 5f,
        cellStiffness = 0.02f,
        linkStiffness = 0.0125f,
        energyActionCost = 0f
    )
)

data class CellSettings(
    val maxEnergy: Float = 5f,
    val cellStiffness: Float = 0.2f,
    val linkStiffness: Float = 0.025f,
    val energyActionCost: Float = 0.0005f,
)


class SubstrateSettings {

    //TODO сделать настройку размера карты через настройки субстрата
    val gridCellWidthSize = 192
    val gridCellHeightSize = 192
    var data = readSettings()
    var cellsSettings: List<CellSettings> = data.cellsSettings.values.toList()

    fun update() {
        data = readSettings()
        cellsSettings = data.cellsSettings.values.toList()
    }

    private fun readSettings(): GlobalSimulationSettings {
        val json = Json()
        json.setOutputType(JsonWriter.OutputType.json)
        json.setUsePrototypes(false)

        val fileHandle = getFileHandle()
        if (!fileHandle.exists()) {
            val defaultSettings = GlobalSimulationSettings()
            val prettyJson = json.prettyPrint(defaultSettings)
            fileHandle.writeString(prettyJson, false)
            return defaultSettings
        }

        try {
            val jsonString = fileHandle.readString()
            return json.fromJson(GlobalSimulationSettings::class.java, jsonString)
        } catch (e: Exception) {
            return GlobalSimulationSettings()
        }
    }

    fun getFileHandle(): FileHandle {
        val relativeFolderName = "settings"
        val fileName = "GlobalSubstrateSettings.json"

        val saveDir: FileHandle = when (Gdx.app.type) {
            Application.ApplicationType.Desktop -> {
                val jarFile =
                    File(GenomeJsonReader::class.java.protectionDomain.codeSource.location.toURI())
                Gdx.files.absolute(jarFile.parentFile.absolutePath)
            }

            Application.ApplicationType.Android -> {
                Gdx.files.local("")
            }

            else -> {
                Gdx.files.local("")
            }
        }

        val folderHandle: FileHandle = saveDir.child(relativeFolderName)
        if (!folderHandle.exists() || !folderHandle.isDirectory) {
            folderHandle.mkdirs()
        }
        return folderHandle.child(fileName)
    }

}
