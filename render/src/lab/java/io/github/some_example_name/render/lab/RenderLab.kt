package io.github.some_example_name.render.lab

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import io.github.some_example_name.render.RenderFrame
import io.github.some_example_name.render.WorldRenderer
import io.github.some_example_name.render.debug.RenderSceneDump
import io.github.some_example_name.render.pack.CellInstanceBuffer
import io.github.some_example_name.render.pack.PheromoneInstanceBuffer
import java.io.File

/**
 * Стенд рендера: рисует сцену без игры.
 *
 * ЗАЧЕМ
 * -----
 * Чтобы менять шейдеры, пересобирая один только :render. Раньше проверить правку в
 * circle.frag можно было единственным способом — собрать core со всей симуляцией,
 * запустить игру, дойти до нужного экрана и глазами поискать разницу. Здесь между
 * правкой шейдера и картинкой — одна клавиша R, вообще без пересборки: шейдеры читаются
 * с диска, а рабочий каталог у задачи — assets/.
 *
 * ЗАПУСК
 * ------
 *   gradlew :render:renderLab
 *       откроет scene-dumps/scene-last.scene (пишется клавишей F9 в симуляции),
 *       а если дампа нет — синтетическую сцену со всеми слоями текстур
 *
 *   gradlew :render:renderLab -Dgenomeia.scene=scene-dumps/scene-1712345678.scene
 *       откроет конкретный снимок
 *
 * УПРАВЛЕНИЕ
 * ----------
 *   R           перечитать шейдеры с диска
 *   P           постпроцесс вкл/выкл
 *   V           виньетка вкл/выкл
 *   B           размытие: имитация рывка камеры
 *   колесо      зум
 *   ЛКМ+тяга    панорама
 *   F           вернуть исходный кадр из дампа
 *   TAB         спрятать подсказку
 *   ESC         выход
 *
 * ЧЕГО ЗДЕСЬ НЕТ И НЕ БУДЕТ
 * -------------------------
 * Симуляции. Стенд рисует ОДИН зафиксированный кадр — это не урезанная игра, а лупа для
 * шейдера. Всё, что он умеет показать, приходит либо из файла дампа, либо из синтетики.
 */

private const val DEFAULT_SCENE = "scene-dumps/scene-last.scene"
private const val SCENE_PROPERTY = "genomeia.scene"
private const val SCREENSHOT_PROPERTY = "genomeia.screenshot"
private const val SCREENSHOT_FRAME_PROPERTY = "genomeia.screenshotFrame"
private const val DEFAULT_SCREENSHOT_FRAME = 60

/** Размер окна, когда рисуем синтетику и подстраиваться не подо что. */
private const val FALLBACK_WIDTH = 1280
private const val FALLBACK_HEIGHT = 800

fun main(args: Array<String>) {
    val requested = args.firstOrNull() ?: System.getProperty(SCENE_PROPERTY)

    // Печатается всегда. Почти все промахи стенда — это промахи по рабочему каталогу
    // (шейдеры, текстуры, дампы ищутся относительно него), а он единственное, чего в
    // сообщении об ошибке обычно не хватает.
    println("рабочий каталог: ${File("").absolutePath}")
    println("каталог assets:  ${findAssetsDirectory()?.absolutePath ?: "НЕ НАЙДЕН"}")

    // Дамп читается ДО старта приложения, чтобы окно открылось того же размера, каким был
    // вьюпорт в игре. Тогда кадр стенда сходится с игровым не «примерно похоже», а точно,
    // и его можно сравнивать со скриншотом попиксельно.
    //
    // FileHandle(File) работает без инициализации Gdx.files: это тип Absolute, а он ходит
    // в java.io напрямую.
    val sceneFile = resolveScene(requested)
    val scene = sceneFile?.let {
        try {
            RenderSceneDump.read(FileHandle(it))
        } catch (e: Exception) {
            println("⚠ не удалось прочитать ${it.path}: ${e.message}")
            println("  рисую синтетическую сцену")
            null
        }
    }

    if (scene == null) {
        // Печатаем ВСЕ проверенные абсолютные пути. Рабочий каталог у задачи — assets/,
        // а дампы лежат в корне проекта, и без этого списка «дамп не найден» ничего не
        // объясняет: непонятно даже, откуда стенд смотрел.
        println("⚠ дамп не найден: ${requested ?: DEFAULT_SCENE}")
        candidatesFor(requested).forEach { println("    искал: ${it.absolutePath}") }
    }
    if (scene == null) {
        println("ℹ синтетическая сцена. Снимок из игры: клавиша F9 в симуляции.")
    } else {
        println("✅ сцена: ${sceneFile?.path} — ${scene.cellCount} клеток, ${scene.pheromoneCount} феромонов")
    }

    val configuration = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Genomeia — стенд рендера")
        setWindowedMode(
            scene?.viewportWidth?.takeIf { it > 0 } ?: FALLBACK_WIDTH,
            scene?.viewportHeight?.takeIf { it > 0 } ?: FALLBACK_HEIGHT
        )
        // Тот же профиль, что у игры (см. Lwjgl3Launcher): набор возможностей GLES 3.0,
        // без SSBO. Без него Gdx.gl30 равен null, и ParticleRenderer молча ничего не рисует.
        setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL30, 3, 0)
        useVsync(true)
    }

    Lwjgl3Application(RenderLab(scene), configuration)
}

/**
 * Путь ищется терпимо: как задан, потом относительно рабочего каталога (assets/), потом
 * на уровень выше — там лежат scene-dumps. Иначе пришлось бы помнить, что рабочий каталог
 * у задачи не корень проекта, а это ровно та мелочь, на которой спотыкаются каждый раз.
 */
private fun resolveScene(requested: String?): File? =
    candidatesFor(requested).firstOrNull { it.isFile }?.absoluteFile

/**
 * Куда смотреть в поисках дампа.
 *
 * Рабочий каталог у задачи renderLab — assets/, а дампы пишутся в корень проекта, то есть
 * на уровень выше. Но стенд запускают и из IDE, где рабочий каталог уже корень. Проверяем
 * оба, плюс ещё уровень вверх — на случай запуска из подкаталога модуля.
 */
private fun candidatesFor(requested: String?): List<File> {
    val path = requested ?: DEFAULT_SCENE
    return listOf(File(path), File("..", path), File("../..", path)).map { it.absoluteFile }
}

/**
 * Каталог assets — поиском вверх от рабочего, а не предположением о нём.
 *
 * Рабочий каталог у стенда разный: задача renderLab запускает его из assets/, IDE — из
 * корня проекта или из каталога модуля. Любая догадка тут неизбежно ломается в одном из
 * случаев, и ломается неприятно: не находятся текстуры или шейдеры, а сообщение при этом
 * ничего не говорит про рабочий каталог, которого никто и не подозревает.
 *
 * Опознаём по наличию shaders внутри — просто по имени «assets» ошибиться легче.
 */
internal fun findAssetsDirectory(): File? {
    var directory: File? = File("").absoluteFile
    repeat(5) {
        val current = directory ?: return null
        // Уже внутри assets.
        if (File(current, "shaders").isDirectory) return current
        val nested = File(current, "assets")
        if (File(nested, "shaders").isDirectory) return nested
        directory = current.parentFile
    }
    return null
}

class RenderLab(private val scene: RenderSceneDump.Scene?) : ApplicationAdapter() {

    private lateinit var worldRenderer: WorldRenderer
    private lateinit var camera: OrthographicCamera
    private lateinit var batch: SpriteBatch
    private lateinit var font: BitmapFont
    private lateinit var hudMatrix: Matrix4

    private val frame = RenderFrame()

    private lateinit var texturePaths: List<String>
    private lateinit var cells: java.nio.ByteBuffer
    private lateinit var pheromones: java.nio.ByteBuffer

    private var usePostProcess = true
    private var vignette = 1f
    private var blur = 0f
    private var showHud = true
    private var reloadCount = 0
    private var lastReloadError: String? = null
    private var frameCounter = 0
    private var screenshotTaken = false
    private var screenshotPending = false

    override fun create() {
        texturePaths = scene?.texturePaths?.takeIf { it.isNotEmpty() } ?: discoverTextures()

        val synthetic = if (scene == null) SyntheticScene.build(texturePaths.size) else null
        cells = scene?.cells ?: synthetic!!.cells
        pheromones = scene?.pheromones ?: synthetic!!.pheromones

        usePostProcess = scene?.usePostProcess ?: true
        vignette = scene?.vignetteEnabled ?: 1f
        blur = scene?.blurAmount ?: 0f

        camera = OrthographicCamera().apply {
            setToOrtho(false, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        }
        resetCamera()

        worldRenderer = WorldRenderer(texturePaths)

        batch = SpriteBatch()
        font = BitmapFont()
        hudMatrix = Matrix4()

        Gdx.input.inputProcessor = LabInput()
    }

    private fun resetCamera() {
        camera.position.set(
            scene?.cameraX ?: SyntheticScene.CENTER_X,
            scene?.cameraY ?: SyntheticScene.CENTER_Y,
            0f
        )
        camera.zoom = scene?.zoom ?: SyntheticScene.ZOOM
        camera.update()
    }

    /**
     * Слои для синтетики: всё, что лежит в assets/cell_textures, по алфавиту.
     *
     * Порядок тут заведомо НЕ игровой — тот задаётся списком клеток в :core, о котором
     * этот модуль не знает. Для «посмотреть, как текстуры выглядят в шейдере» этого
     * достаточно; когда важен точный тип клетки — нужен дамп, в нём порядок свой.
     *
     * Отсев по размеру обязателен: TextureArray требует одинаковых слоёв, а в каталоге
     * лежит не только то, что использует игра (например, texture.png на 256×256, которого
     * в списке клеток нет). Игра собирает список поимённо и мимо таких файлов проходит,
     * стенд же берёт каталог целиком — и без отсева падал бы на ровном месте.
     */
    private fun discoverTextures(): List<String> {
        // Каталог берём АБСОЛЮТНЫМ путём, а не через Gdx.files.internal.
        //
        // Для internal на десктопе перечисление содержимого работает только когда каталог
        // реально лежит относительно рабочего; запасной путь через classpath умеет читать
        // ФАЙЛ, но не умеет перечислять КАТАЛОГ. Из IDE рабочий каталог — корень проекта,
        // и список молча выходил пустым.
        val assets = findAssetsDirectory()
            ?: error(
                "не найден каталог assets. Рабочий каталог: ${File("").absolutePath}. " +
                    "Запускайте стенд через gradlew :render:renderLab"
            )

        val files = Gdx.files.absolute(File(assets, "cell_textures").absolutePath).list()
            .filter { it.extension().equals("png", ignoreCase = true) }

        check(files.isNotEmpty()) {
            "в ${File(assets, "cell_textures").absolutePath} не найдено ни одной png"
        }

        val bySize = files.groupBy { pngSize(it) }
        // Берём самую многочисленную группу: это заведомо «нормальный» размер слоя.
        val dominant = bySize.maxBy { it.value.size }

        bySize.forEach { (size, group) ->
            if (size != dominant.key) {
                println("ℹ пропускаю ${group.joinToString { it.name() }} — размер $size, " +
                    "а у слоёв ${dominant.key}")
            }
        }

        return dominant.value.map { "cell_textures/${it.name()}" }.sorted()
    }

    /** Ширина×высота из заголовка IHDR, без загрузки пикселей: он всегда в байтах 16..23. */
    private fun pngSize(file: FileHandle): String {
        val header = ByteArray(24)
        file.read().use { stream ->
            var read = 0
            while (read < header.size) {
                val n = stream.read(header, read, header.size - read)
                if (n < 0) return "?"
                read += n
            }
        }
        fun intAt(offset: Int): Int =
            ((header[offset].toInt() and 0xFF) shl 24) or
                ((header[offset + 1].toInt() and 0xFF) shl 16) or
                ((header[offset + 2].toInt() and 0xFF) shl 8) or
                (header[offset + 3].toInt() and 0xFF)
        return "${intAt(16)}×${intAt(20)}"
    }

    override fun render() {
        if (usePostProcess) {
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        } else {
            Gdx.gl.glClearColor(0.7f, 0.678f, 0.599f, 1f)
        }
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        frame.apply {
            cameraProjection = camera.combined
            cells = this@RenderLab.cells
            uploadCells = true
            pheromones = this@RenderLab.pheromones.takeIf { it.remaining() > 0 }
            pheromoneK = scene?.pheromoneK ?: SyntheticScene.PHEROMONE_K
            pheromoneP = scene?.pheromoneP ?: SyntheticScene.PHEROMONE_P
            blurAmount = blur
            zoom = camera.zoom
            vignetteEnabled = vignette
            usePostProcess = this@RenderLab.usePostProcess
        }
        worldRenderer.render(frame)

        if (blur > 0f) blur -= 0.09f

        if (showHud && !screenshotPending) drawHud()

        captureIfRequested()
    }

    /**
     * Снимок стенда для сравнения с игрой: -Dgenomeia.screenshot=путь.png
     *
     * Подсказку в кадр не пускаем — в ней FPS, а он от запуска к запуску разный, и
     * попиксельное сравнение сразу теряет смысл.
     */
    private fun captureIfRequested() {
        val path = System.getProperty(SCREENSHOT_PROPERTY) ?: return
        if (screenshotTaken) return

        frameCounter++
        val target = System.getProperty(SCREENSHOT_FRAME_PROPERTY)?.toIntOrNull()
            ?: DEFAULT_SCREENSHOT_FRAME
        // Кадр перед съёмкой рисуется уже без подсказки, поэтому прячем её заранее.
        if (frameCounter >= target - 1) screenshotPending = true
        if (frameCounter < target) return

        screenshotTaken = true
        val pixmap = Pixmap.createFromFrameBuffer(
            0,
            0,
            Gdx.graphics.backBufferWidth,
            Gdx.graphics.backBufferHeight
        )
        try {
            val file = if (path.startsWith("/") || (path.length > 1 && path[1] == ':')) {
                Gdx.files.absolute(path)
            } else {
                Gdx.files.local(path)
            }
            // Кадровый буфер читается снизу вверх, PNG пишется сверху вниз — отсюда flipY.
            PixmapIO.writePNG(file, pixmap, -1, true)
            println("✅ снимок стенда: $path")
        } finally {
            pixmap.dispose()
        }
        Gdx.app.exit()
    }

    /**
     * Перечитать шейдеры: WorldRenderer читает их в конструкторе, поэтому пересоздаём его
     * целиком. Дорого — но это ровно то действие, ради которого стенд и существует.
     *
     * Ошибка компиляции шейдера НЕ должна ронять стенд: правишь GLSL — опечатки неизбежны,
     * и терять из-за них окно вместе с настроенной камерой невыносимо. Поэтому старый
     * рендер отпускаем только после того, как новый собрался.
     */
    private fun reloadShaders() {
        val previous = worldRenderer
        try {
            val rebuilt = WorldRenderer(texturePaths)
            previous.dispose()
            worldRenderer = rebuilt
            reloadCount++
            lastReloadError = null
            println("✅ шейдеры перечитаны (#$reloadCount)")
        } catch (e: Exception) {
            lastReloadError = e.message?.take(400) ?: e.toString()
            println("❌ шейдер не собрался, оставляю прежний:\n$lastReloadError")
        }
    }

    private fun drawHud() {
        batch.projectionMatrix = hudMatrix.setToOrtho2D(
            0f,
            0f,
            Gdx.graphics.width.toFloat(),
            Gdx.graphics.height.toFloat()
        )
        batch.begin()

        val source = if (scene == null) "синтетика" else "дамп"
        val instances = cells.remaining() / CellInstanceBuffer.STRUCT_SIZE
        val pheromoneCount = pheromones.remaining() / PheromoneInstanceBuffer.STRUCT_SIZE

        font.draw(
            batch,
            buildString {
                appendLine("FPS ${Gdx.graphics.framesPerSecond}   источник: $source")
                appendLine("клеток $instances   феромонов $pheromoneCount   слоёв ${texturePaths.size}")
                appendLine("zoom ${"%.4f".format(camera.zoom)}   " +
                    "камера ${"%.1f".format(camera.position.x)}, ${"%.1f".format(camera.position.y)}")
                appendLine("постпроцесс ${onOff(usePostProcess)}   виньетка ${onOff(vignette > 0f)}   перезагрузок $reloadCount")
                appendLine()
                appendLine("R перечитать шейдеры   P постпроцесс   V виньетка   B размытие")
                appendLine("колесо зум   ЛКМ панорама   F исходный кадр   TAB скрыть   ESC выход")
                lastReloadError?.let {
                    appendLine()
                    appendLine("ОШИБКА ШЕЙДЕРА (рисую прежним):")
                    appendLine(it)
                }
            },
            12f,
            Gdx.graphics.height - 12f
        )
        batch.end()
    }

    private fun onOff(value: Boolean) = if (value) "вкл" else "выкл"

    override fun resize(width: Int, height: Int) {
        if (width == 0 || height == 0) return
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        camera.update()
        worldRenderer.resize(width, height)
    }

    override fun dispose() {
        worldRenderer.dispose()
        batch.dispose()
        font.dispose()
    }

    private inner class LabInput : InputAdapter() {

        private var dragging = false
        private var lastX = 0f
        private var lastY = 0f

        override fun keyDown(keycode: Int): Boolean {
            when (keycode) {
                Input.Keys.R -> reloadShaders()
                Input.Keys.P -> usePostProcess = !usePostProcess
                Input.Keys.V -> vignette = if (vignette > 0f) 0f else 1f
                Input.Keys.B -> blur = 4f
                Input.Keys.F -> resetCamera()
                Input.Keys.TAB -> showHud = !showHud
                Input.Keys.ESCAPE -> Gdx.app.exit()
                else -> return false
            }
            return true
        }

        override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            dragging = true
            lastX = screenX.toFloat()
            lastY = screenY.toFloat()
            return true
        }

        override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            dragging = false
            return true
        }

        override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
            if (!dragging) return false
            val dx = (screenX - lastX) * camera.zoom
            val dy = (screenY - lastY) * camera.zoom
            // По Y знак обратный: экран растёт вниз, мир — вверх.
            camera.position.add(-dx, dy, 0f)
            camera.update()
            lastX = screenX.toFloat()
            lastY = screenY.toFloat()
            return true
        }

        override fun scrolled(amountX: Float, amountY: Float): Boolean {
            // Зум к курсору, а не к центру экрана: разглядывать всегда нужно конкретную
            // клетку, и без этого она уезжает из вида на первом же щелчке колеса.
            val pointer = Vector3(Gdx.input.x.toFloat(), Gdx.input.y.toFloat(), 0f)
            val before = camera.unproject(pointer.cpy())

            camera.zoom = MathUtils.clamp(
                camera.zoom * if (amountY > 0) 1.1f else 1f / 1.1f,
                0.0001f,
                1000f
            )
            camera.update()

            val after = camera.unproject(pointer.cpy())
            camera.position.add(before.x - after.x, before.y - after.y, 0f)
            camera.update()
            return true
        }
    }
}
