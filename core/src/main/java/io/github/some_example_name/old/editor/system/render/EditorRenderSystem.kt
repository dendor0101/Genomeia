package io.github.some_example_name.old.editor.system.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import io.github.some_example_name.old.cells.base.CellTypeList
import io.github.some_example_name.old.cells.eyeData
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.currentTick
import io.github.some_example_name.old.editor.entities.CellReplay
import io.github.some_example_name.old.editor.entities.NeuralLinkReplay
import io.github.some_example_name.old.editor.system.simulation.EditorSimulationSystem
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.render.DebugCellPalette
import io.github.some_example_name.render.DebugFilter
import io.github.some_example_name.render.RenderFrame
import io.github.some_example_name.render.RenderMode
import io.github.some_example_name.render.WorldRenderer
import io.github.some_example_name.render.pack.CellInstanceBuffer
import io.github.some_example_name.render.pack.CellLook
import io.github.some_example_name.render.pack.OverlayInstanceBuffer
import it.unimi.dsi.fastutil.ints.IntArrayList
import kotlin.math.cos
import kotlin.math.sin

/**
 * Сторона редактора генома в отрисовке кадра.
 *
 * От RenderSystem отличается только источником данных: там снимок живого мира, здесь
 * запись роста организма по тикам (CellReplay). Дальше по конвейеру идут те же самые
 * упакованные байты в тот же самый [WorldRenderer] — ровно ради этого переиспользования
 * упаковка и вынесена в CellInstanceBuffer.
 *
 * Редактор рисует только отладочным режимом и только без фильтра (оригинал). Другие режимы
 * здесь отключены: микроскоп на телефоне в редакторе не рисовал ничего, кроме меток
 * деления (баг игрока 20.11), а редактору нужна схема клеток, а не красивая картинка.
 * Клетки красятся и подписываются по тем же правилам, что в симуляции (DebugCellPalette):
 * цвет из генома, рисунок типа, стрелки, лучи глаз, жгутики и нейросвязи — шейдером.
 *
 * Галочки отладки — свои у редактора. Связи и граница считаются по самим клеткам
 * ([EditorBodyMesh]): мира физики у редактора нет. Цвет из генома включён по умолчанию,
 * как в симуляции; без него — цвет типа. Нейросвязи (включены) можно скрыть. Заливки между
 * клетками и переключателей физики, как в симуляции, здесь нет.
 */
class EditorRenderSystem(
    val worldRenderer: WorldRenderer,
    val cellReplay: CellReplay,
    val neuralLinkReplay: NeuralLinkReplay,
    val particleEntity: ParticleEntity,
    val cellEntity: CellEntity,
    val cellList: CellTypeList,
    val editorSimulationSystem: EditorSimulationSystem,
    val drawingHelperElements: DrawingHelperElements
) {

    private lateinit var camera: OrthographicCamera

    /** Переиспользуются между кадрами. */
    private val cellBuffer = CellInstanceBuffer()
    private val overlayBuffer = OverlayInstanceBuffer()
    private val frame = RenderFrame()

    private val palette = DebugCellPalette(cellList)

    /** Оверлей пишется прямо в буфер инстансов: у редактора нет второго потока. */
    private val overlaySink = DebugCellPalette.OverlaySink { x0, y0, x1, y1, color, params ->
        overlayBuffer.put(x0, y0, x1, y1, color, params)
    }

    // Галочки отладки. Меняются только командами (EditorLogicSystem).

    /** «Связи»: все связи тела тонкими линиями. */
    var showLinks = false
        set(value) { field = value; isUpdateBuffer = true }

    /** «Граница»: контур тела — края, которые омывает среда, и края дыр. */
    var showBoundary = false
        set(value) { field = value; isUpdateBuffer = true }

    /**
     * «Цвет из генома»: цвет, заданный в геноме, а не цвет типа клетки. В редакторе включён
     * по умолчанию: игрок красит клетки сам и ждёт увидеть свой цвет.
     */
    var genomeColors = true
        set(value) { field = value; isUpdateBuffer = true }

    /** «Нейросвязи»: рисовать нейросвязи. Включены по умолчанию. */
    var showNeuralLinks = true

    var isUpdateBuffer = true

    /**
     * Часы анимации рисунков отладочного режима (хлоропласты, реснички, жгутики). Только
     * для картинки: в запись роста и в повтор .bug не попадают.
     */
    private var animationTime = 0f

    /** Положения и радиусы клеток кадра в порядке буфера — вход [mesh]. */
    private var cellCount = 0
    private var cellX = FloatArray(0)
    private var cellY = FloatArray(0)
    private var cellR = FloatArray(0)

    private val mesh = EditorBodyMesh()

    fun create(
        shapeRenderer: ShapeRenderer,
        camera: OrthographicCamera
    ) {
        worldRenderer.checkResize()
        this.camera = camera
        drawingHelperElements.create(shapeRenderer, camera)
    }

    fun resize(width: Int, height: Int) {
        worldRenderer.resize(width, height)
    }

    fun render(touchedCellX: Float, touchedCellY: Float) {
        if (isUpdateBuffer) {
            fillBuffer()
        }
        animationTime = RenderFrame.advanceTime(animationTime, Gdx.graphics.deltaTime)

        frame.apply {
            cameraProjection = camera.combined
            cells = cellBuffer.end()
            // Всегда true, хотя буфер мог и не меняться.
            //
            // Соблазнительно поставить сюда isUpdateBuffer и сэкономить заливку текстуры,
            // но WorldRenderer один на всю игру, и текстура данных в нём общая с экраном
            // симуляции. Пропустив заливку на первом кадре после смены экрана, редактор
            // показал бы чужие частицы. Экономия того не стоит.
            uploadCells = true
            // Редактор феромоны не рисует: в записи роста их нет.
            // Каждый кадр, а не только при isUpdateBuffer: связи и стрелки идут за живыми
            // позициями частиц.
            overlay = fillOverlay()
            // Заливки между клетками в редакторе нет: в позе покоя клетки и так закрывают
            // свои треугольники почти целиком.
            areaIndices = null
            // Сигналов в записи роста нет, импульсам по связям бежать нечем, но рисунки
            // клеток живут и без сигнала: хлоропласты текут, реснички бьют.
            time = animationTime
            mode = RenderMode.DEBUG
            debugFilter = DebugFilter.ORIGINAL
        }
        worldRenderer.render(frame)

        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
        Gdx.gl.glDepthMask(false)
        Gdx.gl.glEnable(GL20.GL_BLEND)

        drawingHelperElements.render(touchedCellX, touchedCellY)
    }

    private fun fillBuffer() {
        cellBuffer.begin()
        cellCount = 0

        cellReplay.forEachInTick(currentTick) { cellType, index, _, angleCos, angleSin, color ->
            val type = cellType.toInt()
            val x = particleEntity.x(index)
            val y = particleEntity.y(index)
            val radius = particleEntity.radius[index]
            cellBuffer.putCell(
                x = x,
                y = y,
                color = if (genomeColors) color else palette.color(type, color),
                angleCos = angleCos,
                angleSin = angleSin,
                radius = radius,
                // Энергии в записи роста нет, а отладочный режим её и не рисует.
                energy = 0f,
                cellType = type,
                // Ключ шума — индекс частицы, как и в живом мире: он закреплён за клеткой
                // и не зависит от порядка сборки буфера.
                noiseSeed = index,
                // Сигналов в записи роста нет: мышцы не сжаты, хвосты не тянут.
                look = palette.look(type, action = 0f)
            )
            rememberCell(x, y, radius)
        }

        // Связи и контур — только по настоящим клеткам: призраки ниже в буфере идут после
        // них и в [mesh] не попадают.
        if (showLinks || showBoundary) mesh.update(cellCount, cellX, cellY, cellR)

        // Клетка-призрак на месте будущего деления: показывает, куда и под каким углом
        // пойдёт дочерняя, пока игрок настраивает деление. Полупрозрачная, с рисунком своего
        // будущего типа: так видно, что за клетка появится на следующей стадии.
        val stage = currentTick
        val stageInstructions = editorSimulationSystem.genomeStageInstruction
        if (stage < stageInstructions.size) {
            val genomeStage = stageInstructions[stage]

            genomeStage.cellActions.forEach { (_, action) ->
                val divide = action.divide
                if (divide != null) {
                    val index = editorSimulationSystem.mapCellGenomeIdToIndex[divide.id]

                    val angle = divide.angle ?: 0f
                    val futureType = divide.cellType?.let(cellList::indexOfKey) ?: -1
                    val ownColor = (divide.color ?: Color.WHITE).toIntBits()

                    cellBuffer.putCell(
                        x = particleEntity.x(index),
                        y = particleEntity.y(index),
                        color = if (futureType >= 0 && !genomeColors) palette.color(futureType, ownColor) else ownColor,
                        angleCos = cos(angle),
                        angleSin = sin(angle),
                        radius = particleEntity.radius[index],
                        energy = 0f,
                        // Пустая текстура, а не текстура какого-то типа: клетки ещё нет.
                        cellType = cellList.emptyTextureLayer,
                        noiseSeed = index,
                        look = if (futureType >= 0) {
                            palette.look(futureType, action = 0f, transparency = DebugCellPalette.GHOST_TRANSPARENCY)
                        } else {
                            CellLook.look(CellLook.PLAIN, transparency = DebugCellPalette.GHOST_TRANSPARENCY)
                        }
                    )
                }
            }
        }
    }

    private fun rememberCell(x: Float, y: Float, radius: Float) {
        if (cellCount == cellX.size) {
            val grown = maxOf(64, cellX.size * 2)
            cellX = cellX.copyOf(grown); cellY = cellY.copyOf(grown); cellR = cellR.copyOf(grown)
        }
        cellX[cellCount] = x
        cellY[cellCount] = y
        cellR[cellCount] = radius
        cellCount++
    }

    /**
     * Оверлей отладочного режима: стрелки, лучи и жгутики у клеток текущего тика, связи и
     * контур тела, нейросвязи — следующего тика (связи показывают, что получится после
     * шага). У клетки, которую сейчас тащат, стрелки нет, а её нейросвязи пропадают, пока
     * она далеко от соседа.
     */
    private fun fillOverlay(): java.nio.ByteBuffer {
        overlayBuffer.begin()
        val grabbed = DIGenomeEditorContainer.grabbedCellIndex

        cellReplay.forEachInTick(currentTick) { cellType, index, _, angleCos, angleSin, _ ->
            if (index == grabbed) return@forEachInTick
            val type = cellType.toInt()
            val x = particleEntity.x(index)
            val y = particleEntity.y(index)
            val radius = particleEntity.radius[index]
            if (palette.isEye(type)) {
                palette.writeEyeRay(
                    overlaySink, type, x, y, angleCos, angleSin, radius,
                    range = cellEntity.specialEntity.eyeData(index).visibilityRange,
                    signal = 0f
                )
            } else if (palette.hasFlagellum(type)) {
                palette.writeFlagellum(overlaySink, type, x, y, angleCos, angleSin, radius, signal = 0f, seed = index)
            } else if (palette.hasArrow(type)) {
                palette.writeArrow(overlaySink, x, y, angleCos, angleSin, radius, signal = 0f)
            }
        }

        // Связи тоньше и под контуром — порядок тот же, что в симуляции (RenderBufferManager).
        if (showLinks) writePairs(mesh.links, OverlayInstanceBuffer.KIND_LINK, DebugCellPalette.LINK_COLOR)
        if (showBoundary) writePairs(mesh.boundary, OverlayInstanceBuffer.KIND_EDGE, DebugCellPalette.BOUNDARY_COLOR)

        if (showNeuralLinks) writeNeuralLinks()
        return overlayBuffer.end()
    }

    private fun writeNeuralLinks() {
        neuralLinkReplay.forEachInTick(DIGenomeEditorContainer.nextStageTick) { isLink1NeuralDirected, color, cellA, cellB ->
            if (!drawingHelperElements.isLinkVisible(cellA, cellB)) return@forEachInTick
            // Сигнал идёт из второй клетки в первую при isLink1NeuralDirected — как в
            // NeuralLinkManager и в шевроне DrawingHelperElements.
            val from = if (isLink1NeuralDirected) cellB else cellA
            val to = if (isLink1NeuralDirected) cellA else cellB
            palette.writeNeuralLink(
                overlaySink,
                particleEntity.x(from), particleEntity.y(from),
                particleEntity.x(to), particleEntity.y(to),
                color,
                signal = 0f,
                seed = from
            )
        }
    }

    /** Отрезки между клетками пар [pairs] (номера буфера клеток кадра). */
    private fun writePairs(pairs: IntArrayList, kind: Int, color: Int) {
        val params = OverlayInstanceBuffer.params(kind, 0f, 0)
        var k = 0
        while (k < pairs.size) {
            val a = pairs.getInt(k)
            val b = pairs.getInt(k + 1)
            k += 2
            overlayBuffer.put(cellX[a], cellY[a], cellX[b], cellY[b], color, params)
        }
    }
}
