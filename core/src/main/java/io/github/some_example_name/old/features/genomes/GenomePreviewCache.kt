package io.github.some_example_name.old.features.genomes

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.old.cells.base.CellTypeList
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.GameFiles
import io.github.some_example_name.old.systems.genomics.genome.GenomePreview
import io.github.some_example_name.old.systems.genomics.library.GenomeCatalog
import io.github.some_example_name.old.systems.genomics.library.GenomeImport
import io.github.some_example_name.render.RenderFrame
import io.github.some_example_name.render.RenderMode
import io.github.some_example_name.render.WorldRenderer
import io.github.some_example_name.render.pack.CellInstanceBuffer

/**
 * Картинки превью геномов для списков: снимок тела из файла генома (Genome.preview) →
 * кадр [WorldRenderer] в свой FBO → текстура.
 *
 * ПОЧЕМУ ПОСТЕПЕННО
 * -----------------
 * Превью — это прочитать файл генома целиком (у «Megalodon» 1.1 МБ protobuf) и прогнать
 * кадр через весь конвейер рендера. На список из десятков геномов разом это заметная
 * пауза при открытии. Поэтому [request] только ставит геном в очередь, а рисует [update]
 * — каждый кадр, пока не выйдет бюджет времени. Картинки появляются по одной, а окно
 * отвечает сразу.
 *
 * Время здесь меряется только для бюджета кадра интерфейса: на мир и повтор бага превью не
 * влияет никак — это чистое отображение файлов.
 *
 * ПОЧЕМУ РЕАЛИСТИЧНЫЙ РЕЖИМ
 * -------------------------
 * Превью всегда реалистичное, как и мир за главным меню: это «лицо» организма. Отладочный
 * режим в буфер превью не рисуется (WorldRenderer.renderOffscreen).
 *
 * ПАМЯТЬ: на геном один FBO [sizePx]², RGBA8 — на 192² это 144 КБ видеопамяти, сотня
 * геномов — 14 МБ. Всё освобождает [dispose]; кэш живёт, пока открыт список.
 *
 * Поток рендера: и [request], и [update] — из кадра интерфейса.
 */
class GenomePreviewCache(
    /** Сторона картинки в пикселях. */
    private val sizePx: Int,
    private val renderer: WorldRenderer = DIGameGlobalContainer.worldRenderer,
    private val cellTypes: CellTypeList = DIGameGlobalContainer.cellTypes
) : Disposable {

    private class Entry(val stamp: Long) {
        var fbo: FrameBuffer? = null
        var region: TextureRegion? = null
        var done = false
        val listeners = ArrayList<(TextureRegion?) -> Unit>(2)
    }

    private val entries = HashMap<String, Entry>()
    private val queue = ArrayDeque<String>()

    private val cells = CellInstanceBuffer(INITIAL_CELLS)
    private val frame = RenderFrame()
    private val projection = Matrix4()

    /**
     * Превью генома [name] (имя из GenomeCatalog). [onReady] получит картинку — сразу,
     * если она уже есть, или в одном из следующих [update]. null — снимка нет (геном не
     * читается или тело пустое): тогда показывать заглушку.
     *
     * Свой геном, изменённый с прошлого запроса (сохранили из редактора), рисуется заново:
     * запись в кэше сверяется со временем изменения файла.
     */
    fun request(name: String, onReady: (TextureRegion?) -> Unit) {
        val stamp = stampOf(name)
        var entry = entries[name]
        if (entry != null && entry.stamp != stamp) {
            entry.fbo?.dispose()
            entry = null
        }
        if (entry == null) {
            entry = Entry(stamp)
            entries[name] = entry
            queue.addLast(name)
        }
        if (entry.done) onReady(entry.region) else entry.listeners += onReady
    }

    /** Нарисовать ожидающие превью, пока не выйдет [budgetNanos]; одно — всегда. */
    fun update(budgetNanos: Long = FRAME_BUDGET_NANOS) {
        if (queue.isEmpty()) return
        val started = System.nanoTime()
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            val entry = entries[name] ?: continue
            if (entry.done) continue
            entry.fbo = renderPreview(name)
            entry.region = entry.fbo?.let { fbo ->
                // Буфер кадра хранится снизу вверх, а интерфейс рисует сверху вниз.
                TextureRegion(fbo.colorBufferTexture).apply { flip(false, true) }
            }
            entry.done = true
            entry.listeners.forEach { it(entry.region) }
            entry.listeners.clear()
            if (System.nanoTime() - started > budgetNanos) break
        }
    }

    /** Забыть геном [name] (удалили или переименовали): его картинка больше не нужна. */
    fun forget(name: String) {
        entries.remove(name)?.fbo?.dispose()
    }

    override fun dispose() {
        entries.values.forEach { it.fbo?.dispose() }
        entries.clear()
        queue.clear()
    }

    private fun renderPreview(name: String): FrameBuffer? {
        // Свой геном могли удалить или переименовать, пока он ждал очереди (в том же кадре):
        // картинка ему уже не нужна, и это не ошибка.
        if (!GenomeCatalog.isBundled(name) && !genomeFile(name).exists()) return null
        val preview = try {
            GenomeCatalog.load(name).preview
        } catch (e: Exception) {
            Gdx.app.error(TAG, "не прочитать геном $name для превью", e)
            null
        }
        if (preview == null || preview.cellCount == 0) return null

        pack(preview)
        // Поле с запасом по краям: обводка собеля и блюр иначе обрезались бы о край.
        val extent = preview.halfExtent() * MARGIN
        projection.setToOrtho2D(-extent, -extent, 2f * extent, 2f * extent)
        frame.apply {
            cameraProjection = projection
            cells = this@GenomePreviewCache.cells.end()
            uploadCells = true
            pheromones = null
            overlay = null
            time = 0f
            // Как в редакторе генома: без размытия и без виньетки.
            blurAmount = -0.04f
            // Единиц мира на пиксель — так же, как зум камеры в игре: от него толщина
            // обводки собеля (PostProcessRenderer).
            zoom = 2f * extent / sizePx
            vignetteEnabled = 0f
            mode = RenderMode.REALISTIC
        }

        val fbo = FrameBuffer(Pixmap.Format.RGBA8888, sizePx, sizePx, false)
        fbo.colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        renderer.renderOffscreen(frame, fbo)
        return fbo
    }

    /** Снимок — в инстансы клеток, как их пакует редактор (EditorRenderSystem.fillBuffer). */
    private fun pack(preview: GenomePreview) {
        val count = preview.cellCount
        cells.begin(count)
        // Ключ → слой текстуры по реестру этого запуска. Тип, которого в нём нет (мод
        // убрали), рисуется пустой текстурой, а не чужой.
        val layerOfKey = IntArray(preview.cellTypeKeys.size) { k ->
            cellTypes.indexOfKey(preview.cellTypeKeys[k]).let { if (it < 0) cellTypes.emptyTextureLayer else it }
        }
        for (k in 0 until count) {
            cells.putCell(
                x = preview.x[k],
                y = preview.y[k],
                color = preview.color(k),
                angleCos = preview.angleCos[k],
                angleSin = preview.angleSin[k],
                radius = preview.radius[k],
                // Реалистичный режим энергию не рисует.
                energy = 0f,
                cellType = layerOfKey[preview.cellType[k]],
                // Ключ шума закреплён за клеткой снимка — текстуры не «крутятся» от раза к разу.
                noiseSeed = k
            )
        }
    }

    /** Время изменения файла своего генома; у встроенных — ноль, они за запуск не меняются. */
    private fun stampOf(name: String): Long =
        if (GenomeCatalog.isBundled(name)) 0L
        else genomeFile(name).lastModified()

    private fun genomeFile(name: String) = GameFiles.genomesDir().child(name + GenomeImport.EXTENSION)

    private companion object {
        const val TAG = "GenomePreview"

        /** Во сколько раз поле шире тела. */
        const val MARGIN = 1.08f

        /** Сколько времени кадра отдавать превью: при 60 к/с кадр — 16.7 мс. */
        const val FRAME_BUDGET_NANOS = 6_000_000L

        const val INITIAL_CELLS = 1024
    }
}
