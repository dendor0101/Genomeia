package io.github.some_example_name.old.features.menu

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Align
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.VisCheckBox
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisImageTextButton
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.DISimulationContainer.particleEntity
import io.github.some_example_name.old.core.ui.makeStyledButton
import io.github.some_example_name.old.core.ui.setupTitleSize
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.EyeEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.NeuralEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.PheromoneEmitterEntity
import io.github.some_example_name.old.entities.PheromoneEntity
import io.github.some_example_name.old.entities.ProducerEntity
import io.github.some_example_name.old.entities.SpecialEntity
import io.github.some_example_name.old.entities.SpecialModDataEntity
import io.github.some_example_name.old.entities.SubstancesEntity
import io.github.some_example_name.old.entities.TailEntity
import io.github.some_example_name.old.entities.samples.CellEntitySample
import io.github.some_example_name.old.features.simulation.SimulationScreen
import io.github.some_example_name.old.features.worldeditor.WorldEditorScreen
import io.github.some_example_name.old.features.worldeditor.WorldGenerator
import io.github.some_example_name.old.game.MyGame
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream


class MapSelect(
    val game: MyGame,
    val title: String
) : VisDialog(title) {
    lateinit var scrollPane: ScrollPane
    private val textures = mutableListOf<Texture>()

    init {
        val scrollContentTable = VisTable()

        setupTitleSize(game)

        setupUI(scrollContentTable)

        scrollPane = ScrollPane(scrollContentTable).apply {
            setFadeScrollBars(false)
            setScrollingDisabled(false, false)
            setForceScroll(false, true)
            setFlickScroll(true)
            setOverscroll(false, true)
        }

        contentTable.add(scrollPane).grow().maxHeight(Gdx.graphics.height * 0.8f)
        contentTable.row()
        closeOnEscape()
        pack()
        centerWindow()
    }

    fun setupUI(scrollContentTable: VisTable) {
        val density = Gdx.graphics.density

        val group = ButtonGroup<VisImageTextButton>()
        group.setMinCheckCount(1) // можно ничего не выбирать
        group.setMaxCheckCount(1) // только один выбран одновременно

        // Используем стиль "radio" для круглых иконок (вместо "default", который для чекбоксов квадратных)
        val radioStyle = VisCheckBox.VisCheckBoxStyle(
            VisUI.getSkin().get("radio", VisCheckBox.VisCheckBoxStyle::class.java)
        )
        val iconSize = if (Gdx.app.type == Application.ApplicationType.Android) 10f else 15f  // Базовый размер иконки (подберите)

        // Устанавливаем размеры для круглой иконки: checkBackground - off (пустой круг), tick - on (точка внутри)
        // checkboxOff не существует; используйте checkBackground и tick из VisCheckBoxStyle
        radioStyle.checkBackground.minWidth = iconSize * density
        radioStyle.checkBackground.minHeight = iconSize * density

        radioStyle.tick.minWidth = iconSize * density  // Размер точки (on state); подберите, чтобы соответствовал background
        radioStyle.tick.minHeight = iconSize * density

        // Для состояний over/down/disabled, если они заданы
        if (radioStyle.checkBackgroundOver != null) {
            radioStyle.checkBackgroundOver.minWidth = iconSize * density
            radioStyle.checkBackgroundOver.minHeight = iconSize * density
        }
        if (radioStyle.checkBackgroundDown != null) {
            radioStyle.checkBackgroundDown.minWidth = iconSize * density
            radioStyle.checkBackgroundDown.minHeight = iconSize * density
        }
        if (radioStyle.tickDisabled != null) {
            radioStyle.tickDisabled.minWidth = iconSize * density
            radioStyle.tickDisabled.minHeight = iconSize * density
        }

        radioStyle.font = if (Gdx.app.type == Application.ApplicationType.Android) game.largeFont else game.extraLargeFont

        val content = VisTable(true)

        val maps = getMaps()
        val btnH = Gdx.graphics.height * 0.055f

        maps.forEachIndexed { index, string ->
            val texture = getTexture(string)
            val iconDrawable: Drawable = TextureRegionDrawable(TextureRegion(texture))

            val button: VisImageTextButton = VisImageTextButton(string, iconDrawable).also {
                it.addListener( object: ClickListener() {
                    override fun clicked(event: InputEvent?, x: Float, y: Float) {
                        val mapName = string
                        val map = getMap(mapName)
                        val old = DIGameGlobalContainer.game.screen
                        old.dispose()

                        // 1. Полный сброс мира перед загрузкой.
                        // Чистить сущности недостаточно: частицы прошлой сессии остаются
                        // зарегистрированными в GridManager, а restoreGridManager() ниже добавляет
                        // те же индексы повторно. Тогда в одной ячейке сетки индекс встречается
                        // дважды, repulse(i, i) получает dx = dy = 0 и все деления на distance
                        // дают NaN, который расползается по всему миру.
                        // dispose() чистит сетку, все сущности из entityList, буферы команд и simulationData.
                        DISimulationContainer.simulationSystem.dispose()
                        getEntities(mapName)

                        // 2. Привязываем зависимости, которые НЕ зависят от GridManager
//                        DISimulationContainer.cellEntity.loadEntity(
//                            particleEntity = DISimulationContainer.particleEntity,
//                            simulationData = DISimulationContainer.simulationData,
//                            substrateSettings = DIGameGlobalContainer.substrateSettings,
//                            neuralEntity = DISimulationContainer.neuralEntity,
//                            specialEntity = DISimulationContainer.specialEntity,
//                            cellList = DISimulationContainer.cellList
//                        )
//                        DISimulationContainer.specialEntity.loadEntity(
//                            eyeEntity = DISimulationContainer.eyeEntity,
//                            tailEntity = DISimulationContainer.tailEntity,
//                            specialModDataEntity = DISimulationContainer.specialModDataEntity,
//                            producerEntity = DISimulationContainer.producerEntity,
//                            pheromoneEmitterEntity = DISimulationContainer.pheromoneEmitterEntity
//                        )
//                        DISimulationContainer.substancesEntity.loadEntity(
//                            particleEntity = DISimulationContainer.particleEntity,
//                            substrateSettings = DIGameGlobalContainer.substrateSettings
//                        )
//                        DISimulationContainer.neuralEntity.loadEntity(DISimulationContainer.cellList)
                        // Остальные сущности без transient-зависимостей (OrganEntity, TailEntity, EyeEntity и т.д.)
                        // можно не привязывать, если они не имеют методов loadEntity с параметрами.

                        // 3. Пересоздаём GridManager и все системы (теперь они увидят уже инициализированные сущности)
                        //DISimulationContainer.reInit()

                        // 4. Привязываем GridManager-зависимые сущности к НОВОМУ GridManager
                        //DISimulationContainer.particleEntity.loadEntity(DISimulationContainer.gridManager)
                        // восстановление индексов в сетке
                        val repaired = DISimulationContainer.particleEntity.restoreGridManager()
                        if (repaired > 0) {
                            println("В сохранении $mapName было $repaired частиц с битыми координатами/скоростями, они восстановлены")
                        }
                        println(DISimulationContainer.particleEntity.x.size)

//                        DISimulationContainer.linkEntity.loadEntity(
//                            cellEntity = DISimulationContainer.cellEntity,
//                            gridManager = DISimulationContainer.gridManager,
//                            particleEntity = DISimulationContainer.particleEntity,
//                            diContext = DISimulationContainer
//                        )
//                        DISimulationContainer.pheromoneEntity.loadEntity(DISimulationContainer.gridManager)

                        // 5. Устанавливаем индекс карты
                        //DISimulationContainer.mapSave.currentMap = mapName.substringBefore('.').toInt()

                        // 6. Открываем экран симуляции
                        close()
                        DIGameGlobalContainer.game.screen = SimulationScreen(null, null)   // <-- map=null сделан специально, не обращать внимания!

                    }
                })
            }
            val size = 64f * density
            button.imageCell.size(size, size).padRight(8f * density)

            button.padRight(size + (8f * density))

            content.add(button).height(btnH).center().row()
            group.add(button)
        }

        scrollContentTable.add(content).pad(10f * density).row()


        val bottomButtonTable = VisTable()
        bottomButtonTable.defaults().padRight(8f * density)


        makeStyledButton("New Map", game, textures).also {
            bottomButtonTable.add(it).height(btnH).row()

            it.addListener( object: ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    hide()
                    val old = game.screen
                    game.screen = WorldEditorScreen()
                    old.dispose()
                }
            })
        }
        scrollContentTable.add(bottomButtonTable).center().padTop(8f*density)
    }

    fun getMap(name: String): Array<BooleanArray> {
        var map: Array<BooleanArray> = emptyArray()
        ZipInputStream(FileInputStream(File(DISimulationContainer.baseMapDir+name))).use { zipIn ->
            var entry = zipIn.nextEntry

            while (entry != null) {
                if (entry.name == "world.bin") {
                    val dataIn = DataInputStream(zipIn).use { data ->
                        val custom = data.readBoolean()
                        println(custom)

                        val width = data.readInt()
                        val height = data.readInt()
                        println("${width}:${height}")

                        if (!custom) {
                            val seed = data.readLong()
                            map = WorldGenerator().generateWorld(width, height, seed)
                        }
                        else {
                            val height = data.readInt()
                            //val flatMap = mutableListOf<Boolean>()
                            //val premap = Array<BooleanArray>(height) {}
                            val mapArray = Array(height) { BooleanArray(0) }

                            for (y in 0 until height) {
                                val width = data.readInt()
                                val bars = BooleanArray(width)
                                for (x in 0 until width) {
                                    bars[x] = data.readBoolean()
                                }
                                mapArray[y] = bars
                            }

                            map = mapArray
                        }

                        data.readInt()
                    }
                    break
                }
                entry = zipIn.nextEntry
            }
        }
        return map
    }

    fun getEntities(name: String) {
        ZipInputStream(FileInputStream(File(DISimulationContainer.baseMapDir+name))).use { zipIn ->
            var entry = zipIn.nextEntry

            while (entry != null) {
                if (entry.name == "CellEntity.bin") {
                    val bytes = zipIn.readBytes()

                    // ДcellEntity
                    var data = ProtoBuf.decodeFromByteArray(CellEntity.serializer(), bytes)

                    data.loadSerializedEntity()
//                    data.loadEntity(particleEntity = DISimulationContainer.particleEntity,
//                        simulationData = DISimulationContainer.simulationData,
//                        substrateSettings = DIGameGlobalContainer.substrateSettings,
//                        neuralEntity = DISimulationContainer.neuralEntity,
//                        specialEntity = DISimulationContainer.specialEntity,
//                        cellList = DISimulationContainer.cellList)

                    //DISimulationContainer.cellEntity = data
                }
                if (entry.name == "SpecialEntity.bin") {
                    val bytes = zipIn.readBytes()

                    var data = ProtoBuf.decodeFromByteArray(SpecialEntity.serializer(), bytes)

                    data.loadSerialize()

//                    data.loadEntity(
//                        eyeEntity = DISimulationContainer.eyeEntity,
//                        tailEntity = DISimulationContainer.tailEntity,
//                        specialModDataEntity = DISimulationContainer.specialModDataEntity,
//                        producerEntity = DISimulationContainer.producerEntity,
//                        pheromoneEmitterEntity = DISimulationContainer.pheromoneEmitterEntity
//                    )

                    //DISimulationContainer.specialEntity = data
                }
                if (entry.name == "SubstanceEntity.bin") {
                    val bytes = zipIn.readBytes()

                    var data = ProtoBuf.decodeFromByteArray(SubstancesEntity.serializer(), bytes)

                    data.loadSerializedEntity()

//                    data.loadEntity(
//                        particleEntity = DISimulationContainer.particleEntity,
//                        substrateSettings = DIGameGlobalContainer.substrateSettings
//                    )

                    //DISimulationContainer.substancesEntity = data
                }
                if (entry.name == "SpecialModDataEntity.bin") {
                    val bytes = zipIn.readBytes()

                    var data = ProtoBuf.decodeFromByteArray(SpecialModDataEntity.serializer(), bytes)

                    data.loadSerialize()

                    //DISimulationContainer.specialModDataEntity = data
                }
                if (entry.name == "TailEntity.bin") {
                    val bytes = zipIn.readBytes()

                    var data = ProtoBuf.decodeFromByteArray(TailEntity.serializer(), bytes)

                    data.loadSerializedEntity()

                    //DISimulationContainer.tailEntity = data
                }
                if (entry.name == "EyeEntity.bin") {
                    val bytes = zipIn.readBytes()

                    var data = ProtoBuf.decodeFromByteArray(EyeEntity.serializer(), bytes)

                    data.loadSerializedEntity()

                    //DISimulationContainer.eyeEntity = data
                }
                if (entry.name == "LinkEntity.bin") {
                    val bytes = zipIn.readBytes()

                    var data = ProtoBuf.decodeFromByteArray(LinkEntity.serializer(), bytes)

                    data.loadSerializedEntity()
                    // data.loadEntity(cellEntity = DISimulationContainer.cellEntity, gridManager = DISimulationContainer.gridManager, particleEntity = DISimulationContainer.particleEntity, diContext = DISimulationContainer)

                    //DISimulationContainer.linkEntity = data
                }
                if (entry.name == "NeuralEntity.bin") {
                    val bytes = zipIn.readBytes()

                    var data = ProtoBuf.decodeFromByteArray(NeuralEntity.serializer(), bytes)

                    data.loadSerializedEntity()
                    //data.loadEntity(DISimulationContainer.cellList)

                    //DISimulationContainer.neuralEntity = data
                }
                if (entry.name == "OrganEntity.bin") {
                    val bytes = zipIn.readBytes()

                    var data = ProtoBuf.decodeFromByteArray(OrganEntity.serializer(), bytes)

                    data.loadSerializedEntity()

                    //DISimulationContainer.organEntity = data
                }
                if (entry.name == "ParticleEntity.bin") {
                    val bytes = zipIn.readBytes()
                    var data = ProtoBuf.decodeFromByteArray(ParticleEntity.serializer(), bytes)
                    data.loadSerializedEntity()

                    println("=== LOADED DATA ===")
                    println("data.lastId = ${data.lastId}")
                    println("data.maxAmount = ${data.maxAmount}")
                    println("data.aliveList.size = ${data.aliveList.size}")
                    println("data.x.size = ${data.x.size}")
                    if (data.aliveList.size > 0) {
                        val firstIdx = data.aliveList.getInt(0)
                        println("first alive: idx=$firstIdx, x=${data.x[firstIdx]}, y=${data.y[firstIdx]}, isAlive=${data.isAlive[firstIdx]}")
                    }

                    DISimulationContainer.particleEntity.copyFrom(data)

                    println("=== AFTER COPY ===")
                    println("container.lastId = ${DISimulationContainer.particleEntity.lastId}")
                    println("container.aliveList.size = ${DISimulationContainer.particleEntity.aliveList.size}")
                    if (DISimulationContainer.particleEntity.aliveList.size > 0) {
                        val firstIdx = DISimulationContainer.particleEntity.aliveList.getInt(0)
                        println("first alive: idx=$firstIdx, x=${DISimulationContainer.particleEntity.x[firstIdx]}, isAlive=${DISimulationContainer.particleEntity.isAlive[firstIdx]}")
                    }
                }
                if (entry.name == "PheromoneEmitterEntity.bin") {
                    val bytes = zipIn.readBytes()

                    var data = ProtoBuf.decodeFromByteArray(PheromoneEmitterEntity.serializer(), bytes)

                    data.loadSerializedEntity()

                    //DISimulationContainer.pheromoneEmitterEntity = data
                }
                if (entry.name == "PheromoneEntity.bin") {
                    val bytes = zipIn.readBytes()

                    var data = ProtoBuf.decodeFromByteArray(PheromoneEntity.serializer(), bytes)

                    data.loadSerializedEntity()
                    //data.loadEntity(DISimulationContainer.gridManager)

                    //DISimulationContainer.pheromoneEntity = data
                }
                if (entry.name == "ProducerEntity.bin") {
                    val bytes = zipIn.readBytes()

                    var data = ProtoBuf.decodeFromByteArray(ProducerEntity.serializer(), bytes)

                    data.loadSerializedEntity()

                    //DISimulationContainer.producerEntity = data
                }
                entry = zipIn.nextEntry
            }
        }
    }

    fun getTexture(name: String): Texture {
        ZipInputStream(FileInputStream(File(DISimulationContainer.baseMapDir+name))).use { zipIn ->
            var entry = zipIn.nextEntry

            while (entry != null) {
                // Find the specific PNG file
                if (entry.name == name.substringBefore(".")+".png" && !entry.isDirectory) {
                    val outputBuffer = ByteArrayOutputStream()

                    // Copy stream data safely using Kotlin's extension function
                    zipIn.copyTo(outputBuffer)

                    zipIn.closeEntry()
                    val bytes = outputBuffer.toByteArray()
                    val pixmap = Pixmap(bytes, 0, bytes.size)
                    return Texture(pixmap)
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
        println("Black")
        val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)

        pixmap.setColor(Color.BLACK)

        pixmap.fill()
        return Texture(pixmap) // File not found
    }

    fun getMaps(): List<String> {
        val file = File(DISimulationContainer.baseMapDir)
        val fileCount = file.listFiles()?.count { it.isFile } ?: 0
        val maps = mutableListOf<String>()
        if (fileCount > 0)
        {
            file.listFiles().forEachIndexed { index, file ->
                maps.add(file.name)
            }
            return maps
        }
        return maps
    }
}
