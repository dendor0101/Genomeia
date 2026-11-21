package io.github.some_example_name.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.TextureData
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer

/**
 * Номер GL-контекста: растёт, когда контекст потерян и создан заново.
 *
 * ЗАЧЕМ
 * -----
 * На Android контекст OpenGL может пропасть, пока игра свёрнута. libGDX просит его
 * сохранить (setPreserveEGLContextOnPause), но система вправе отобрать его всё равно: при
 * нехватке памяти, а на части GPU всегда. Тогда AndroidGraphics.onSurfaceCreated
 * пересоздаёт СВОИ объекты: шейдеры, Mesh, текстуры, FrameBuffer. О сырых VBO и VAO,
 * которые рендер ведёт сам (CellGeometry, OverlayGeometry, DebugAreaGeometry, микроскоп),
 * libGDX не знает. Их имена в новом контексте ничего не значат, и мир перестаёт рисоваться
 * до перезапуска игры.
 *
 * Сигнала «контекст пересоздан» libGDX не даёт. Поэтому здесь своя управляемая текстура
 * 1×1 с данными типа Custom: при пересоздании контекста libGDX перезаливает все
 * управляемые текстуры и зовёт её consumeCustomData. Этот вызов и служит сигналом. Цена в
 * кадре — сравнение двух int в WorldRenderer.
 *
 * На десктопе и iOS контекст не теряется, и номер после старта не меняется.
 */
internal object GlContextWatch {

    /** Растёт при каждой заливке текстуры-сторожа: при создании и после потери контекста. */
    var generation = 0
        private set

    private var canary: Texture? = null

    /** Начать следить. Звать из потока GL; повторный вызов ничего не делает. */
    fun ensureStarted() {
        if (canary == null) canary = Texture(CanaryData)
    }

    private object CanaryData : TextureData {
        override fun getType() = TextureData.TextureDataType.Custom
        override fun isPrepared() = true
        override fun prepare() = Unit
        override fun consumePixmap(): Pixmap = throw UnsupportedOperationException("custom data")
        override fun disposePixmap() = false

        override fun consumeCustomData(target: Int) {
            Gdx.gl.glTexImage2D(target, 0, GL20.GL_RGBA, 1, 1, 0, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, null)
            generation++
        }

        override fun getWidth() = 1
        override fun getHeight() = 1
        override fun getFormat() = Pixmap.Format.RGBA8888
        override fun useMipMaps() = false

        /** Управляемая — иначе libGDX не перезальёт её после потери контекста. */
        override fun isManaged() = true
    }
}

/**
 * Освободить FrameBuffer, который libGDX пересобрал после потери контекста, чтобы потом
 * создать его заново.
 *
 * Починить такой буфер на месте нельзя из-за libGDX (1.14.2): GLFrameBuffer.build() при
 * пересборке ДОБАВЛЯЕТ новые текстуры к старым и не убирает старые. В начале
 * textureAttachments остаются мёртвые имена, и colorBufferTexture и textureAttachments[i]
 * смотрят на них. Буфер с одним вложением к тому же привязан к мёртвой текстуре
 * (attachFrameBufferColorTexture(textureAttachments.first())), то есть рисовать в него
 * нельзя вовсе.
 *
 * Обычный dispose удалил бы и мёртвые имена. А в новом контексте те же номера уже могут
 * принадлежать чужим текстурам (шрифту, иконкам), поэтому мёртвые сначала убираются из
 * списка: живые — последние [attachmentCount], столько, сколько вложений у буфера. Так
 * верно и после нескольких пересборок подряд.
 */
internal fun releaseRebuiltFrameBuffer(fbo: GLFrameBuffer<*>, attachmentCount: Int) {
    val attachments = fbo.textureAttachments
    val stale = attachments.size - attachmentCount
    if (stale > 0) attachments.removeRange(0, stale - 1)
    fbo.dispose()
}
