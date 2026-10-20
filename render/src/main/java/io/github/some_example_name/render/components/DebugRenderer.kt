package io.github.some_example_name.render.components

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.GL30
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import io.github.some_example_name.render.RenderMode

/**
 * Отладочный режим: клетки цвета своего типа со схемой-рисунком (CellLook — хлоропласты,
 * волокна мышцы, реснички) на тёмном фоне, как на стенде RealBodyDemo, и поверх —
 * нейросвязи с бегущими импульсами, стрелки направлений, жгутики и лучи глаз. Рисунки
 * анимированы часами кадра и сигналом действия клетки — см. cell_common.glsl.
 *
 * Всё сглажено аналитически: каждая фигура знает расстояние от пикселя до своего края и
 * сама считает, какую долю пикселя закрывает. Это то, что дал бы MSAA, но без
 * мультисэмплового буфера и без лишних выборок — экранный буфер у игры без MSAA, и
 * заводить его ради одного режима значило бы платить за все.
 *
 * ПРОХОДЫ
 * -------
 *  1. Заливка клеток в свой FBO: цвет, точная глубина d/R и (вторым буфером) градиент
 *     d/R владельца пикселя. Тест GL_LESS оставляет в пикселе клетку с наименьшим d/R —
 *     это диаграмма Вороного, как в ностальгическом режиме. Глубина очищается значением
 *     d/R = 1: пустота — тоже «клетка», и она выигрывает там, где ни одна клетка до
 *     пикселя не дотягивается. У внешнего края тела владелец сам сглаживает край внутрь
 *     (cell_fill.frag).
 *  2. Заливка из FBO — на экран, поверх фона (composite.frag).
 *  3. Сглаживание: клетки ещё раз, прямо в экран, с чтением глубины и градиента из
 *     прохода 1. Каждая клетка знает, насколько она проиграла владельцу пикселя и как
 *     быстро растёт проигрыш, то есть как далеко её граница, и подмешивает свой цвет в
 *     полупиксельную полосу у неё, заодно рисуя стенку между клетками (cell_edge.frag).
 *     Над пустотой за окружностью тут же рисуется бахрома рисунков — реснички, иглы,
 *     шипы, — а полупрозрачные клетки («призрак» в редакторе) рисуются здесь целиком.
 *  4. Оверлей: нейросвязи, стрелки, лучи, кольца, жгутики — инстансами по кваду
 *     (overlay.frag). Жгутик читает владельцев пикселей из прохода 1: над клетками его нет.
 *
 * Глубина нужна проходу 3 как ТЕКСТУРА, поэтому у режима свой FBO с текстурой глубины, а
 * не scene FBO из WorldRenderer: у того глубина в рендербуфере, читать её нельзя.
 * Размер — ровно экранный буфер в пикселях: проходы 2 и 3 читают его по gl_FragCoord.
 *
 * Чего здесь нет: физических связей. Режим показывает тело и нервную систему, а не
 * пружины решателя — их по-прежнему видно на стенде RealBodyDemo.
 *
 * ЦЕНА (12.10, RenderLab, синтетика dense: 20 000 частиц на весь экран 1280×800,
 * GL_RENDERER = Intel UHD Graphics, медиана GPU на прогон): прежняя отладка — текстурные
 * клетки ParticleRenderer прямо в экран, без сглаживания — 2.28 мс; этот режим без
 * оверлея — 3.22 мс, на уровне реалистичного (3.11). Разница — второй проход по клеткам
 * (выборка глубины, большинство фрагментов отбрасывается сразу за ней) и вывод FBO на
 * экран. Оверлей стоит пропорционально числу и длине связей, стрелок и лучей.
 *
 * ЦЕНА РИСУНКОВ (20.10, та же сцена и тот же GPU; старые и новые шейдеры по очереди при
 * одном и том же Kotlin, чтобы фоновая нагрузка машины легла на оба варианта):
 *  - вдали (радиус клетки 7–10 пикселей, рисунки ещё не включаются): 3.8–4.4 → 4.1–4.9 мс;
 *  - вблизи (радиус ~33 пикселя, весь экран — клетки со случайными рисунками, худший
 *    случай): 2.0–2.1 → 3.2–3.7 мс; без рисунков те же шейдеры — 2.0–2.4 мс. Для сравнения,
 *    реалистичный режим на этой сцене — 2.9 мс.
 *  - по рисункам вблизи (у всех клеток один): без рисунка 2.15, пигменты 2.77, кость 2.87,
 *    лист 3.09, сенсор касания 3.14, мышца 3.15, нейрон 3.33, контроллер 3.56, синапс 3.75.
 * Что уже сделано ради цены: рисунок включается с радиуса 10 пикселей (patternFade), в
 * полосе сглаживания у соседа — только ткань без рисунка, случайные числа клетки — в
 * вершинном шейдере, угол — полиномом вместо atan, корпус под бахрому раздувается только
 * у крупных клеток. Дальше дешевле — только убрав перекрытие корпусов в заливке (рисунок
 * сейчас считается и для проигравших фрагментов, на плотной сцене ~1.5 раза на пиксель).
 */
class DebugRenderer(private val cells: CellGeometry) : RenderComponent {

    private val overlay = OverlayGeometry()

    private lateinit var fillShader: ShaderProgram
    private lateinit var edgeShader: ShaderProgram
    private lateinit var compositeShader: ShaderProgram
    private lateinit var overlayShader: ShaderProgram

    private var fbo: FrameBuffer? = null
    private var fboWidth = 0
    private var fboHeight = 0

    override fun create() {
        fillShader = compile("cell.vert", "cell_fill.frag")
        edgeShader = compile("cell.vert", "cell_edge.frag")
        compositeShader = ShaderProgram(
            Gdx.files.internal("shaders/post_process/post_process.vert").readString(),
            source("composite.frag")
        ).also(::check)
        overlayShader = compile("overlay.vert", "overlay.frag")
        overlay.create()
    }

    private fun compile(vertex: String, fragment: String): ShaderProgram =
        ShaderProgram(source(vertex), source(fragment)).also(::check)

    private fun check(shader: ShaderProgram) {
        if (!shader.isCompiled) {
            throw RuntimeException("Debug shader compilation failed: ${shader.log}")
        }
    }

    /**
     * Исходник шейдера с подставленными #include.
     *
     * В GLSL ES своего #include нет, а общие функции у двух проходов по клеткам (и
     * размеры у вершинного и фрагментного шейдеров оверлея) обязаны совпадать — копия в
     * двух файлах разъехалась бы при первой правке. Поэтому строка `#include "файл"`
     * заменяется содержимым файла из того же каталога, по-простому, без вложенности.
     */
    private fun source(name: String): String {
        val text = Gdx.files.internal(SHADER_DIR + name).readString()
        return INCLUDE.replace(text) { match ->
            Gdx.files.internal(SHADER_DIR + match.groupValues[1]).readString()
        }
    }

    override fun resize(width: Int, height: Int) {
        // FBO сверяется с экранным буфером каждый кадр, см. ensureFbo.
    }

    /**
     * FBO в размер ЭКРАННОГО буфера в пикселях, а не логического окна: проходы 2 и 3
     * читают его по gl_FragCoord, и на экране с масштабом (HiDPI) логический размер
     * разъехался бы с пиксельным. Сверка каждый кадр — два сравнения, зато размер не
     * зависит от того, кто и когда позвал resize.
     */
    private fun ensureFbo(): FrameBuffer {
        val width = Gdx.graphics.backBufferWidth.coerceAtLeast(1)
        val height = Gdx.graphics.backBufferHeight.coerceAtLeast(1)
        fbo?.let { existing ->
            if (fboWidth == width && fboHeight == height) return existing
            existing.dispose()
        }

        // Порядок вложений = индексы COLOR/OWNER/DEPTH_ATTACHMENT ниже. Два цветовых — это
        // MRT: libGDX сам включает оба через glDrawBuffers.
        val built = GLFrameBuffer.FrameBufferBuilder(width, height)
            .addBasicColorTextureAttachment(Pixmap.Format.RGBA8888)
            .addBasicColorTextureAttachment(Pixmap.Format.RGBA8888)
            .addDepthTextureAttachment(GL30.GL_DEPTH_COMPONENT24, GL20.GL_UNSIGNED_INT)
            .build()

        // Глубина 24 бита не фильтруется в GLES 3.0: с LINEAR, который ставит libGDX,
        // текстура неполная и читается нулями. Да и читаем мы её texelFetch'ем.
        built.textureAttachments.forEach {
            it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
        }

        fbo = built
        fboWidth = width
        fboHeight = height
        return built
    }

    override fun render(context: RenderContext) {
        if (context.mode != RenderMode.DEBUG) return

        val target = ensureFbo()
        val gl = Gdx.gl

        fill(context, target)
        composite(context, target.textureAttachments[COLOR_ATTACHMENT])
        edges(
            context,
            depth = target.textureAttachments[DEPTH_ATTACHMENT],
            owner = target.textureAttachments[OWNER_ATTACHMENT]
        )
        drawOverlay(context, owner = target.textureAttachments[OWNER_ATTACHMENT])

        // Состояние — как его ждут остальные проходы и интерфейс.
        gl.glDisable(GL20.GL_BLEND)
        gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        gl.glDepthMask(true)
    }

    /** Проход 1: заливка и глубина d/R в свой FBO. */
    private fun fill(context: RenderContext, target: FrameBuffer) {
        val gl = Gdx.gl
        target.begin()

        gl.glClearColor(0f, 0f, 0f, 0f)
        // d/R = 1 — пустота. Клетки пишут только d/R < 1 и выигрывают у неё внутри круга.
        gl.glClearDepthf(DEPTH_SCALE)
        gl.glDepthMask(true)
        gl.glDisable(GL20.GL_BLEND)
        gl.glEnable(GL20.GL_DEPTH_TEST)
        gl.glDepthFunc(GL20.GL_LESS)
        gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

        fillShader.bind()
        bindCellUniforms(fillShader, context, fringe = false)
        cells.draw(context.numInstances, CellGeometry.Detail.HULL)

        target.end()

        // Значение очистки — общее состояние контекста; остальные его не ставят и ждут 1.
        gl.glClearDepthf(1f)
        gl.glDisable(GL20.GL_DEPTH_TEST)
    }

    /** Проход 2: фон и заливка на экран. */
    private fun composite(context: RenderContext, color: Texture) {
        val gl = Gdx.gl
        gl.glDepthMask(false)
        gl.glDisable(GL20.GL_BLEND)

        compositeShader.bind()
        compositeShader.setUniformi("u_texture", 0)
        compositeShader.setUniformf("u_background", BACKGROUND_R, BACKGROUND_G, BACKGROUND_B)
        gl.glActiveTexture(GL20.GL_TEXTURE0)
        color.bind()

        context.fullscreenMesh.bind(compositeShader)
        gl.glDrawArrays(GL20.GL_TRIANGLE_STRIP, 0, 4)
        context.fullscreenMesh.unbind(compositeShader)
    }

    /** Проход 3: сглаживание границ и стенки — в экран, с чтением глубины прохода 1. */
    private fun edges(context: RenderContext, depth: Texture, owner: Texture) {
        val gl = Gdx.gl
        gl.glEnable(GL20.GL_BLEND)
        // Шейдер отдаёт цвет, уже умноженный на покрытие.
        gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA)

        edgeShader.bind()
        bindCellUniforms(edgeShader, context, fringe = true)
        setIfPresent(edgeShader, "u_depth", 0)
        setIfPresent(edgeShader, "u_owner", 1)
        depth.bind(0)
        owner.bind(1)
        // Texture.bind(unit) оставляет активным свой блок; остальные ждут нулевой.
        gl.glActiveTexture(GL20.GL_TEXTURE0)

        cells.draw(context.numInstances, CellGeometry.Detail.HULL)
    }

    /**
     * Проход 4: нейросвязи, стрелки, лучи, кольца, жгутики. [owner] — владельцы пикселей
     * из заливки: жгутик виден только над пустотой.
     */
    private fun drawOverlay(context: RenderContext, owner: Texture) {
        val data = context.overlayData ?: return
        if (context.numOverlayInstances <= 0) return

        overlay.upload(data)

        val gl = Gdx.gl
        gl.glEnable(GL20.GL_BLEND)
        gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA)

        overlayShader.bind()
        overlayShader.setUniformMatrix("u_projTrans", context.cameraProjection)
        setIfPresent(overlayShader, "u_pixelWorld", context.pixelWorld)
        setIfPresent(overlayShader, "u_time", context.time)
        setIfPresent(overlayShader, "u_owner", 0)
        owner.bind(0)
        overlay.draw(context.numOverlayInstances)
    }

    /**
     * [fringe] — раздуть корпус клетки на бахрому рисунка (реснички, иглы): она нужна
     * только проходу сглаживания, заливке лишние фрагменты ни к чему.
     */
    private fun bindCellUniforms(shader: ShaderProgram, context: RenderContext, fringe: Boolean) {
        shader.setUniformMatrix("u_projTrans", context.cameraProjection)
        // Компилятор вправе выкинуть неиспользуемый униформ — отсюда setIfPresent, а не
        // setUniformf с его исключением.
        setIfPresent(shader, "u_pixelWorld", context.pixelWorld)
        setIfPresent(shader, "u_depthScale", DEPTH_SCALE)
        setIfPresent(shader, "u_fringe", if (fringe) 1f else 0f)
        setIfPresent(shader, "u_time", context.time)
        val location = shader.fetchUniformLocation("u_background", false)
        if (location >= 0) shader.setUniformf(location, BACKGROUND_R, BACKGROUND_G, BACKGROUND_B)
    }

    private fun setIfPresent(shader: ShaderProgram, name: String, value: Float) {
        val location = shader.fetchUniformLocation(name, false)
        if (location >= 0) shader.setUniformf(location, value)
    }

    private fun setIfPresent(shader: ShaderProgram, name: String, value: Int) {
        val location = shader.fetchUniformLocation(name, false)
        if (location >= 0) shader.setUniformi(location, value)
    }

    override fun dispose() {
        if (::fillShader.isInitialized) fillShader.dispose()
        if (::edgeShader.isInitialized) edgeShader.dispose()
        if (::compositeShader.isInitialized) compositeShader.dispose()
        if (::overlayShader.isInitialized) overlayShader.dispose()
        overlay.dispose()
        fbo?.dispose()
        fbo = null
    }

    companion object {
        private const val SHADER_DIR = "shaders/debug_view/"

        private val INCLUDE = Regex("""(?m)^#include\s+"([^"]+)"\s*$""")

        private const val COLOR_ATTACHMENT = 0
        private const val OWNER_ATTACHMENT = 1
        private const val DEPTH_ATTACHMENT = 2

        /**
         * Глубина окна = d/R * DEPTH_SCALE. Половина, а не единица: тогда очистка (d/R = 1)
         * — это 0.5, и у раздутого под сглаживание корпуса есть запас за окружностью.
         */
        private const val DEPTH_SCALE = 0.5f

        /** Фон — тот же тёмно-синий, что у стенда RealBodyDemo (BG = #12161E). */
        const val BACKGROUND_R = 0x12 / 255f
        const val BACKGROUND_G = 0x16 / 255f
        const val BACKGROUND_B = 0x1E / 255f
    }
}
