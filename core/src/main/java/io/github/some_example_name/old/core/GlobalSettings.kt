package io.github.some_example_name.old.core

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.utils.Json
import com.badlogic.gdx.utils.JsonWriter
import io.github.some_example_name.old.core.DIGameGlobalContainer.defaultCellSettingsMap

data class GlobalSimulationSettings(
    var amountOfSolarEnergy: Float = 0.06f,
    var viscosityOfTheEnvironment: Float = 0.03f,
    var tailMaxSpeedCoefficient: Float = 0.00625f,
    var finMaxSpeedCoefficient: Float = 0.0625f,
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

/** initialData/defaults позволяют маленькому миру работать без файлов и настроек игрока. */
class SubstrateSettings(
    initialData: GlobalSimulationSettings? = null,
    private val defaults: Map<String, CellSettings>? = null
) {

    var data = initialData ?: readSettings()

    /**
     * Настройки по номеру типа клетки. Собираются по реестру клеток (порядок
     * defaultCellSettingsMap), а не по порядку в файле игрока: в файле может не быть новой
     * клетки или порядок может быть другим, и тогда номер типа указывал бы на чужие настройки.
     */
    var cellsSettings: List<CellSettings> = byCellType(data)

    fun update() {
        data = readSettings()
        cellsSettings = byCellType(data)
    }

    private fun byCellType(data: GlobalSimulationSettings): List<CellSettings> =
        (defaults ?: defaultCellSettingsMap).map { (name, default) -> data.cellsSettings[name] ?: default }

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

    /** Файл настроек среды. Каталог — см. GameFiles.settingsDir (при повторе бага — песочница). */
    fun getFileHandle(): FileHandle = GameFiles.settingsDir().child(FILE_NAME)

    companion object {
        const val FILE_NAME = "GlobalSubstrateSettings-0.2.4.1.test.json"
    }

}
