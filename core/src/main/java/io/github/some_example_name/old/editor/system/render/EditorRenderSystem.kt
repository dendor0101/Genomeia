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
import io.github.some_example_name.render.RenderFrame
import io.github.some_example_name.render.RenderMode
import io.github.some_example_name.render.WorldRenderer
import io.github.some_example_name.render.pack.CellInstanceBuffer
import io.github.some_example_name.render.pack.CellLook
import io.github.some_example_name.render.pack.OverlayInstanceBuffer
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
 * В отладочном режиме клетки красятся и подписываются по тем же правилам, что в
 * симуляции (DebugCellPalette): цвет и рисунок типа, стрелки, лучи глаз, жгутики и
 * нейросвязи — шейдером, а не линиями ShapeRenderer.
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

    var isUpdateBuffer = true

    /**
     * Часы анимации рисунков отладочного режима (хлоропласты, реснички, жгутики). Только
     * для картинки: в запись роста и в повтор .bug не попадают.
     */
    private var animationTime = 0f

    /**
     * Режим рендера редактора — свой, выбирается списком на экране редактора и с режимом
     * симуляции не связан. Смена режима требует перезаливки буфера (см. [fillBuffer]),
     * поэтому выставлять его — через этот сеттер, он об этом позаботится.
     */
    var mode: RenderMode = RenderMode.REALISTIC
        set(value) {
            if (field != value) isUpdateBuffer = true
            field = value
        }

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
            pheromones = null
            // Каждый кадр, а не только при isUpdateBuffer: связи и стрелки идут за живыми
            // позициями частиц, как и линии DrawingHelperElements, которые они заменяют.
            overlay = if (mode.drawsDebugOverlay) fillOverlay() else null
            // Сигналов в записи роста нет, импульсам по связям бежать нечем, но рисунки
            // клеток живут и без сигнала: хлоропласты текут, реснички бьют.
            time = animationTime
            blurAmount = -0.04f
            zoom = camera.zoom
            vignetteEnabled = 0f
            mode = this@EditorRenderSystem.mode
        }
        worldRenderer.render(frame)

        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
        Gdx.gl.glDepthMask(false)
        Gdx.gl.glEnable(GL20.GL_BLEND)

        drawingHelperElements.render(
            touchedCellX,
            touchedCellY,
            drawsLinksAndDirections = !mode.drawsDebugOverlay
        )
    }

    private fun fillBuffer() {
        cellBuffer.begin()

        // Энергии в записи роста нет. Реалистичный и отладочный режимы её и не рисуют. В
        // ностальгическом же ядро — это то, чем клетка отличается от субстанции, поэтому там
        // клетки получают энергию, которую симуляция редактора и так держит у всех
        // (EditorSimulationSystem: 5).
        val energy = if (mode == RenderMode.NOSTALGIC) NOSTALGIC_ENERGY else 0f
        val debug = mode == RenderMode.DEBUG

        cellReplay.forEachInTick(currentTick) { cellType, index, _, angleCos, angleSin, color ->
            val type = cellType.toInt()
            cellBuffer.putCell(
                x = particleEntity.x[index],
                y = particleEntity.y[index],
                color = if (debug) palette.color(type, color) else color,
                angleCos = angleCos,
                angleSin = angleSin,
                radius = particleEntity.radius[index],
                energy = energy,
                cellType = type,
                // Ключ шума — индекс частицы, как и в живом мире: он закреплён за клеткой
                // и не зависит от порядка сборки буфера.
                noiseSeed = index,
                // Сигналов в записи роста нет: мышцы не сжаты, хвосты не тянут.
                look = if (debug) palette.look(type, action = 0f) else 0
            )
        }

        // Клетка-призрак на месте будущего деления: показывает, куда и под каким углом
        // пойдёт дочерняя, пока игрок настраивает деление. В реалистичном режиме она
        // невидима (пустая текстура), в отладочном — полупрозрачная, с рисунком своего
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
                        x = particleEntity.x[index],
                        y = particleEntity.y[index],
                        color = if (debug && futureType >= 0) palette.color(futureType, ownColor) else ownColor,
                        angleCos = cos(angle),
                        angleSin = sin(angle),
                        radius = particleEntity.radius[index],
                        energy = 0f,
                        // Пустая текстура, а не текстура какого-то типа: клетки ещё нет.
                        cellType = cellList.emptyTextureLayer,
                        noiseSeed = index,
                        look = if (!debug) 0 else if (futureType >= 0) {
                            palette.look(futureType, action = 0f, transparency = DebugCellPalette.GHOST_TRANSPARENCY)
                        } else {
                            CellLook.look(CellLook.PLAIN, transparency = DebugCellPalette.GHOST_TRANSPARENCY)
                        }
                    )
                }
            }
        }
    }

    /**
     * Оверлей отладочного режима: стрелки, лучи и жгутики у клеток текущего тика, нейросвязи —
     * следующего (как и у DrawingHelperElements: связи показывают, что получится после
     * шага). У клетки, которую сейчас тащат, стрелки нет, а её связи пропадают, пока она
     * далеко от соседа, — тоже как было.
     */
    private fun fillOverlay(): java.nio.ByteBuffer {
        overlayBuffer.begin()
        val grabbed = DIGenomeEditorContainer.grabbedCellIndex

        cellReplay.forEachInTick(currentTick) { cellType, index, _, angleCos, angleSin, _ ->
            if (index == grabbed) return@forEachInTick
            val type = cellType.toInt()
            val x = particleEntity.x[index]
            val y = particleEntity.y[index]
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

        neuralLinkReplay.forEachInTick(DIGenomeEditorContainer.nextStageTick) { isLink1NeuralDirected, color, cellA, cellB ->
            if (!drawingHelperElements.isLinkVisible(cellA, cellB)) return@forEachInTick
            // Сигнал идёт из второй клетки в первую при isLink1NeuralDirected — как в
            // NeuralLinkManager и в шевроне DrawingHelperElements.
            val from = if (isLink1NeuralDirected) cellB else cellA
            val to = if (isLink1NeuralDirected) cellA else cellB
            palette.writeNeuralLink(
                overlaySink,
                particleEntity.x[from], particleEntity.y[from],
                particleEntity.x[to], particleEntity.y[to],
                color,
                signal = 0f,
                seed = from
            )
        }
        return overlayBuffer.end()
    }

    private companion object {
        /** Энергия клеток редактора в ностальгическом режиме — см. [fillBuffer]. */
        const val NOSTALGIC_ENERGY = 5f
    }
}
