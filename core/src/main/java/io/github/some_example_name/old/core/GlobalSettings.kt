package io.github.some_example_name.old.core

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.utils.Json
import com.badlogic.gdx.utils.JsonWriter
import io.github.some_example_name.old.core.DIGameGlobalContainer.defaultCellSettingsMap
import io.github.some_example_name.old.systems.genomics.genome_deprecated.GenomeJsonReader
import java.io.File

data class GlobalSimulationSettings(
    var amountOfSolarEnergy: Float = 0.06f,
    var viscosityOfTheEnvironment: Float = 0.03f,
    var tailMaxSpeedCoefficient: Float = 0.00625f,
    var producerRestoreTimeTickCoefficient: Float = 4f,
    var amountOfFoodEnergy: Float = 4f,
    var rateOfEnergyTransferInLinks: Float = 0.03f,
    var rateOfEnergyTransferForPumper: Float = 0.03f,
    var rateOfPheromoneDiffusion: Float = 4.0e-3f,
    var rateOfPheromoneDegradation: Float = 1.6e-4f,
    var theNumberOfTicksHungryCellDies: Int = 200,
    var gravity: Float = 0.0f,
    var cellsSettings: Map<String, CellSettings> = defaultCellSettingsMap
)

data class CellSettings(
    var maxEnergy: Float = 5f,
    var cellStiffness: Float = 0.2f,
    var linkStiffness: Float = 0.025f,
    var energyActionCost: Float = 0.0005f,
)

class SubstrateSettings {

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
        val fileName = "GlobalSubstrateSettings-0.2.4.test.json"

        val saveDir: FileHandle = when (Gdx.app.type) {
            Application.ApplicationType.Desktop -> {
//                val jarFile =
//                    File(GenomeJsonReader::class.java.protectionDomain.codeSource.location.toURI())
//                Gdx.files.absolute(jarFile.parentFile.absolutePath)
                Gdx.files.local("")
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
