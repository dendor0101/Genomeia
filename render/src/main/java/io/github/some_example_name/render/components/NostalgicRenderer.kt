package io.github.some_example_name.render.components

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import io.github.some_example_name.render.RenderMode

/**
 * Ностальгический режим: кружки по диаграмме Вороного, как в первых версиях игры.
 *
 * Каждая частица — круг своего цвета; там, где круги перекрываются, граница проходит по
 * взвешенной диаграмме Вороного (её строит буфер глубины, см. circle_pc.vert). У клетки в
 * центре чёрное ядро, у не-клетки ядра нет. По краю круга и вдоль границ с соседями —
 * контур темнее заливки. Фон чёрный, постпроцесса нет.
 *
 * КАК ПОЛУЧАЕТСЯ КОНТУР
 * ---------------------
 * Обозначим f = d/R — глубину клетки в пикселе. Буфер глубины хранит только
 * минимум f по клеткам, то есть владельца пикселя. Для контура же нужно знать, есть ли
 * рядом ВТОРАЯ клетка: пиксель лежит на границе, если какая-то другая клетка j почти так
 * же близка, как владелец, —
 *
 *     f_j < f_владельца + δ,
 *
 * ровно то же условие |d1 - d2| < порог, что считал старый сеточный шейдер, только в
 * метрике d/R. Второго минимума буфер глубины не хранит, но его и не нужно хранить —
 * достаточно ПОСЧИТАТЬ, сколько клеток проходят это условие. Это делает stencil:
 *
 *  1. Заливка. Клетки рисуются как обычно, но глубина каждой поднята на δ: в буфере
 *     остаётся f_владельца + δ. Цвет — заливка и ядро (nostalgic.frag).
 *  2. Подсчёт. Те же клетки ещё раз, уже без подъёма, без записи цвета и глубины. Каждый
 *     фрагмент, прошедший тест глубины, прибавляет единицу в stencil. Сам владелец
 *     проходит всегда (f < f + δ), сосед — только если он ближе порога. Внешний обод —
 *     это граница с пустотой, и её размечает одна полноэкранная плоскость на глубине
 *     «f = 1 - ширина обода»: она проходит там, где владелец дальше этой черты.
 *     Итог: stencil >= 2 ровно там, где нужен контур.
 *  3. Затемнение. Полноэкранный квад с тестом stencil >= 2 умножает кадр на [SHADE].
 *
 * Свойства, ради которых выбран этот путь, а не карта id с поиском границы по соседним
 * пикселям: результат точный и не зависит от зума (поиск границы дорожает с размером
 * клетки на экране), нет ни выборок из текстур, ни MRT. Обод и граница с соседом метят
 * один и тот же stencil, поэтому пиксель, попавший под оба, затемняется один раз, а не
 * дважды. Платой служит второй проход по геометрии.
 *
 * ТОЧНАЯ ГЛУБИНА ВМЕСТО КОНУСА
 * ----------------------------
 * f считается во фрагментном шейдере точно, length() от локальной координаты, и пишется
 * в gl_FragDepth; геометрия — восьмиугольник вокруг круга (CellGeometry.Detail.HULL), а
 * лишнее за окружностью отсекает discard.
 *
 * Сначала f, как в реалистичном режиме, интерполировалась по вееру-конусу. Конус
 * многоугольный и ошибается до 1 - cos(π/N), а край контура — это пересечение двух
 * таких поверхностей (заливки и подсчёта), поэтому на сильном приближении он шёл волной
 * с периодом граней. С точной f окружности, границы клеток и контур точные при любом зуме.
 *
 * Early-Z при этом теряется: перекрытые фрагменты проходят шейдер. Здесь это дёшево —
 * фрагмент стоит length(), сравнение и запись цвета, — а вершин стало 10 на клетку вместо
 * 34. На замере (Intel UHD, 20 000 частиц, 1280×800) вышло быстрее, чем с конусом: 2.06
 * против 2.12 мс на мелких клетках и 1.23 против 1.53 мс на крупных. На тайловых GPU
 * (Mali, Adreno, Apple) gl_FragDepth отключает и их отсечение скрытых поверхностей; при
 * таком лёгком фрагменте это та же цена, но если ностальгический режим на телефоне
 * просядет — смотреть сюда первым делом.
 *
 * Рисует в scene FBO, а не прямо в экран: stencil у экранного буфера есть не на всех
 * платформах, а у FBO он свой (WorldRenderer создаёт его с depth+stencil). Готовый кадр
 * копируется на экран одним квадом.
 *
 * Геометрия клеток общая с ParticleRenderer — [CellGeometry], залита до всех проходов.
 */
class NostalgicRenderer(private val cells: CellGeometry) : RenderComponent {

    private lateinit var fillShader: ShaderProgram
    private lateinit var countShader: ShaderProgram
    private lateinit var planeShader: ShaderProgram
    private lateinit var presentShader: ShaderProgram

    override fun create() {
        fillShader = compile("shaders/nostalgic/nostalgic.vert", "shaders/nostalgic/nostalgic.frag")
        countShader = compile("shaders/nostalgic/nostalgic.vert", "shaders/nostalgic/count.frag")
        planeShader = compile("shaders/nostalgic/plane.vert", "shaders/nostalgic/plane.frag")
        presentShader = compile("shaders/post_process/post_process.vert", "shaders/nostalgic/present.frag")
    }

    private fun compile(vertexPath: String, fragmentPath: String): ShaderProgram {
        val program = ShaderProgram(
            Gdx.files.internal(vertexPath).readString(),
            Gdx.files.internal(fragmentPath).readString()
        )
        if (!program.isCompiled) {
            throw RuntimeException("Nostalgic shader $fragmentPath compilation failed: ${program.log}")
        }
        return program
    }

    override fun resize(width: Int, height: Int) {
        // Своих буферов нет: рисуем в scene FBO, которым владеет WorldRenderer.
    }

    override fun render(context: RenderContext) {
        if (context.mode != RenderMode.NOSTALGIC) return
        val sceneFbo = context.sceneFbo ?: return
        val gl = Gdx.gl

        sceneFbo.begin()

        // Фон — часть рисунка, поэтому цвет очистки задаём здесь, а не берём у экрана.
        gl.glClearColor(0f, 0f, 0f, 1f)
        gl.glClearStencil(0)
        gl.glStencilMask(0xFF)
        gl.glDisable(GL20.GL_BLEND)
        gl.glEnable(GL20.GL_DEPTH_TEST)
        gl.glDepthFunc(GL20.GL_LESS)
        gl.glDepthMask(true)
        gl.glClear(
            GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT or GL20.GL_STENCIL_BUFFER_BIT
        )

        fill(context)
        countContour(context)
        darkenContour(context)

        // Состояние — как его ждут остальные проходы конвейера.
        gl.glDisable(GL20.GL_STENCIL_TEST)
        gl.glDisable(GL20.GL_BLEND)
        gl.glDepthFunc(GL20.GL_LESS)
        gl.glDepthMask(true)

        sceneFbo.end()

        present(context, sceneFbo.colorBufferTexture)
    }

    /** Проход 1: заливка и ядро, конусы подняты на [CONTOUR_WIDTH]. */
    private fun fill(context: RenderContext) {
        fillShader.bind()
        fillShader.setUniformMatrix("u_projTrans", context.cameraProjection)
        fillShader.setUniformf("u_nucleusAtFullEnergy", NUCLEUS_AT_FULL_ENERGY)
        fillShader.setUniformf("u_depthBias", CONTOUR_WIDTH)
        fillShader.setUniformf("u_depthScale", DEPTH_SCALE)
        cells.draw(context.numInstances, CellGeometry.Detail.HULL)
    }

    /** Проход 2: сколько частиц ближе порога — в stencil. Цвет и глубина не пишутся. */
    private fun countContour(context: RenderContext) {
        val gl = Gdx.gl
        gl.glColorMask(false, false, false, false)
        gl.glDepthMask(false)
        gl.glEnable(GL20.GL_STENCIL_TEST)
        gl.glStencilFunc(GL20.GL_ALWAYS, 0, 0xFF)
        // INCR насыщается на 255, а не переполняется: стыки многих клеток безопасны.
        gl.glStencilOp(GL20.GL_KEEP, GL20.GL_KEEP, GL20.GL_INCR)

        countShader.bind()
        countShader.setUniformMatrix("u_projTrans", context.cameraProjection)
        countShader.setUniformf("u_depthScale", DEPTH_SCALE)
        // Тот же корпус и та же точная d/R, что у заливки, — без подъёма на δ. Поверхности
        // совпадают до бита, так что владелец засчитывает свой пиксель всегда.
        cells.draw(context.numInstances, CellGeometry.Detail.HULL)

        // Внешний обод — граница с пустотой. Плоскость на глубине «f = 1 - RIM_WIDTH»
        // проходит тест там, где f владельца + δ больше неё, то есть ровно на ободе.
        drawPlane(
            depth = (1f - RIM_WIDTH + CONTOUR_WIDTH) * DEPTH_SCALE,
            r = 0f, g = 0f, b = 0f,
            context = context
        )

        gl.glColorMask(true, true, true, true)
    }

    /** Проход 3: затемнить всё, что размечено дважды и больше, — один раз. */
    private fun darkenContour(context: RenderContext) {
        val gl = Gdx.gl
        // ref <= stencil, то есть stencil >= 2: владелец плюс хотя бы ещё кто-то.
        gl.glStencilFunc(GL20.GL_LEQUAL, 2, 0xFF)
        gl.glStencilOp(GL20.GL_KEEP, GL20.GL_KEEP, GL20.GL_KEEP)

        // Только поверх частиц: у фона глубина 1.0 (очистка), у частиц — меньше
        // APPLY_DEPTH. Сейчас фон чёрный и умножение ему не повредило бы, но контур
        // принадлежит частицам, а не пустоте вокруг них.
        gl.glDepthFunc(GL20.GL_GREATER)

        gl.glEnable(GL20.GL_BLEND)
        gl.glBlendFunc(GL20.GL_ZERO, GL20.GL_SRC_COLOR)
        drawPlane(depth = APPLY_DEPTH, r = SHADE, g = SHADE, b = SHADE, context = context)
    }

    private fun drawPlane(depth: Float, r: Float, g: Float, b: Float, context: RenderContext) {
        planeShader.bind()
        planeShader.setUniformf("u_depth", depth)
        planeShader.setUniformf("u_color", r, g, b, 1f)
        context.fullscreenMesh.bind(planeShader)
        Gdx.gl.glDrawArrays(GL20.GL_TRIANGLE_STRIP, 0, 4)
        context.fullscreenMesh.unbind(planeShader)
    }

    /** Готовый кадр — на экран или в буфер превью (RenderContext.outputFbo). */
    private fun present(context: RenderContext, frame: Texture) {
        val gl = Gdx.gl
        context.beginOutput()
        gl.glDisable(GL20.GL_DEPTH_TEST)
        gl.glDisable(GL20.GL_BLEND)

        presentShader.bind()
        presentShader.setUniformi("u_texture", 0)
        gl.glActiveTexture(GL20.GL_TEXTURE0)
        frame.bind()

        context.fullscreenMesh.bind(presentShader)
        gl.glDrawArrays(GL20.GL_TRIANGLE_STRIP, 0, 4)
        context.fullscreenMesh.unbind(presentShader)
        context.endOutput()
    }

    override fun dispose() {
        if (::fillShader.isInitialized) fillShader.dispose()
        if (::countShader.isInitialized) countShader.dispose()
        if (::planeShader.isInitialized) planeShader.dispose()
        if (::presentShader.isInitialized) presentShader.dispose()
    }

    companion object {
        /**
         * Радиус чёрного ядра клетки, в долях её радиуса, при энергии ENERGY_SPAN (10) —
         * больше байт энергии не вмещает.
         *
         * Ядро растёт с энергией линейно, как и в старом сеточном шейдере: 0.04R на единицу
         * энергии. Обычная клетка (maxEnergy = 5) с полным запасом получает 0.2R, жир и
         * зигота (10) — 0.4R. Без энергии ядра нет вовсе.
         */
        const val NUCLEUS_AT_FULL_ENERGY = 0.4f

        /** Ширина тёмного обода по внешнему краю круга, в долях радиуса клетки. */
        const val RIM_WIDTH = 0.2f

        /**
         * Порог δ контура между соседями, в единицах d/R.
         *
         * Для двух клеток одного радиуса на линии центров разность d/R меняется как
         * 2t/R, где t — расстояние до границы. Значит, полоса |f_A - f_B| < δ имеет
         * ширину δ·R на ДВЕ клетки, по δ/2 на каждую. При δ = 0.2 линия между клетками
         * той же толщины, что и внешний обод, — как на кадрах первых версий игры.
         *
         * Дальше от линии центров полоса расширяется: граница |d_A - d_B| = const — это
         * гипербола. Отсюда «вдавленный» вид контура у стыков, он и был в оригинале.
         */
        const val CONTOUR_WIDTH = 0.2f

        /** Контур — это цвет частицы, умноженный на это число. */
        const val SHADE = 0.7f

        /**
         * Масштаб глубины: самая дальняя точка заливки, d/R = 1 плюс подъём δ, обязана
         * лечь строго ниже единицы, которой чистится буфер глубины, — иначе обод не прошёл
         * бы GL_LESS и по краю каждой клетки появилось бы кольцо дыр. Отсюда 0.99.
         */
        private const val DEPTH_SCALE = 0.99f / (1f + CONTOUR_WIDTH)

        /** Глубина квада затемнения: выше любой частицы (max 0.99), ниже фона (1.0). */
        private const val APPLY_DEPTH = 0.995f
    }
}
