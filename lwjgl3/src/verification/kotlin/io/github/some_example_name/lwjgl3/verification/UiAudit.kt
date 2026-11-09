package io.github.some_example_name.lwjgl3.verification

import com.badlogic.gdx.*
import com.badlogic.gdx.backends.lwjgl3.*
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.*
import com.badlogic.gdx.scenes.scene2d.ui.*
import io.github.some_example_name.lwjgl3.DesktopFileProvider
import io.github.some_example_name.old.game.MyGame
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.commands.*
import io.github.some_example_name.old.bug.ReplayTarget
import io.github.some_example_name.old.features.guide.*
import io.github.some_example_name.old.features.settings.*
import java.io.File
import java.lang.reflect.Proxy
import java.util.Locale
import kotlin.system.exitProcess

// Только verification: настоящий UI и GPU; подменена лишь сообщаемая плотность экрана.
fun main() {
    val w = System.getProperty("audit.w", "360").toInt()
    val h = System.getProperty("audit.h", "640").toInt()
    val d = System.getProperty("audit.d", "1").toFloat()
    val lang = System.getProperty("audit.lang", "ru")
    Locale.setDefault(Locale.forLanguageTag(lang))
    val out = File(System.getProperty("audit.out")).apply { mkdirs() }
    val game = MyGame(DesktopFileProvider())
    var index = -1
    var frame = 0
    var failed = false
    var failedStep = false
    val steps = mutableListOf<Pair<String, () -> Unit>>()
    fun nav(name: String) { DIGameGlobalContainer.navigationCommandsManager.performCommand(navigationCommandByName(name)!!) }
    fun replay(command: GameCommand) { check((game.screen as ReplayTarget).replay(command)) }
    fun route(name: String) { steps += name to { nav(name) } }
    route("GoMenu")
    route("GoSettings")
    SettingsTab.entries.forEach { tab -> steps += "settings-$tab" to { replay(SettingsIntent.SelectTab(tab)) } }
    route("GoSupport")
    route("GoGenomes")
    route("GoWorldEditor")
    route("GoGenomeEditor")
    route("GoTutorial")
    route("GoGuide")
    GuideTab.entries.forEach { tab -> steps += "guide-$tab" to { replay(GuideIntent.SelectTab(tab)) } }
    listOf("leaf", "fat", "bone", "tail", "controller", "muscle", "sucker", "excreta", "punisher", "pumper", "zygote", "producer", "neuron", "suction_cup", "sticky", "chameleon", "eye", "touch_trigger", "synapse", "pheromone_emitter", "pheromone_sensor", "delay").forEach { key ->
        steps += "lab-$key" to { replay(GuideIntent.CloseCellLab); replay(GuideIntent.OpenCellLab(key)) }
    }
    route("GoSimulation")
    route("GoEcoSystem")
    val config = Lwjgl3ApplicationConfiguration().apply {
        setPreferencesConfig(java.nio.file.Files.createTempDirectory("genomeia-ui-audit-").toString(), Files.FileType.Absolute)
        setTitle("Genomeia UI audit $w x $h @ $d $lang")
        setWindowedMode(w,h); useVsync(false); setForegroundFPS(30)
        setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL30,3,3)
        disableAudio(true)
    }
    File(out,"progress.txt").writeText("")
    val rows = File(out,"actors.tsv")
    rows.writeText("screen\ttype\ttext\txDp\tyDp\twDp\thDp\tprefWDp\tprefHDp\tfontDp\tscrollAncestor\toutside\n")
    fun stages(p: InputProcessor?): List<Stage> = when(p) {
        is Stage -> listOf(p)
        is InputMultiplexer -> p.processors.flatMap { stages(it) }
        else -> emptyList()
    }
    fun capture(name: String) {
        val pix = Pixmap.createFromFrameBuffer(0,0,Gdx.graphics.backBufferWidth,Gdx.graphics.backBufferHeight)
        PixmapIO.writePNG(Gdx.files.absolute(File(out,"$name.png").absolutePath),pix,-1,true); pix.dispose()
        fun walk(a: Actor, scroll: Boolean) {
            if (!a.isVisible) return
            val pos = a.localToStageCoordinates(Vector2())
            if(a is Label || a is Button || a is Slider || a is SelectBox<*> || a is TextField) {
                val text = when(a) { is Label -> a.text.toString(); is TextButton -> a.text.toString(); is TextField -> a.text; else -> "" }.replace('\t',' ').replace('\n',' ')
                val layout = a as? com.badlogic.gdx.scenes.scene2d.utils.Layout
                val outside = pos.x < -1 || pos.y < -1 || pos.x+a.width>Gdx.graphics.width+1 || pos.y+a.height>Gdx.graphics.height+1
                rows.appendText(listOf(name,a.javaClass.simpleName.ifEmpty { a.javaClass.superclass.simpleName },text,pos.x/d,pos.y/d,a.width/d,a.height/d,(layout?.prefWidth?:0f)/d,(layout?.prefHeight?:0f)/d,if(a is Label) a.style.font.capHeight*a.fontScaleY/d else 0f,scroll,outside).joinToString("\t")+"\n")
            }
            if(a is Group) a.children.toList().forEach { walk(it,scroll || a is ScrollPane) }
        }
        stages(Gdx.input.inputProcessor).distinct().forEach { walk(it.root,false) }
        File(out,"progress.txt").appendText("$name ${Gdx.graphics.width}x${Gdx.graphics.height} density=${Gdx.graphics.density} type=${Gdx.app.type}\n")
    }
    if(System.getProperty("audit.details") == "true") {
        steps.clear()
        fun actors(a: Actor): List<Actor> = listOf(a) + if(a is Group) a.children.flatMap { actors(it) } else emptyList()
        fun allActors() = stages(Gdx.input.inputProcessor).flatMap { actors(it.root) }
        fun click(key: String) {
            val text = if (key in listOf("editor-tools", "debug-filter-button")) key else DIGameGlobalContainer.bundle.get(key)
            val a = allActors().lastOrNull { (it.name == key || it is TextButton && it.text.toString()==text) && it.isVisible }
            checkNotNull(a) { "Missing button $key" }
            val pos = a.localToStageCoordinates(Vector2(a.width/2,a.height/2))
            a.stage.stageToScreenCoordinates(pos)
            if(pos.x !in 0f..Gdx.graphics.width.toFloat() || pos.y !in 0f..Gdx.graphics.height.toFloat()) {
                error("Unreachable button $key at $pos")
            }
            Gdx.input.inputProcessor.touchDown(pos.x.toInt(),pos.y.toInt(),0,Input.Buttons.LEFT)
            Gdx.input.inputProcessor.touchUp(pos.x.toInt(),pos.y.toInt(),0,Input.Buttons.LEFT)
        }
        fun bottom(name: String) { steps += name to { allActors().filterIsInstance<ScrollPane>().forEach { it.scrollPercentY=1f; it.updateVisualScroll() } } }
        route("GoSupport"); bottom("about-bottom")
        route("GoSettings")
        steps += "graphics" to { replay(SettingsIntent.SelectTab(SettingsTab.GRAPHICS)) }
        bottom("graphics-bottom")
        route("GoWorldEditor")
        steps += "world-settings" to { click("button.settings") }
        bottom("world-settings-bottom")
        route("GoGenomeEditor")
        steps += "editor-filter" to { click("debug-filter-button") }
        steps += "editor-energy" to { click("debugFilter.energy") }
        steps += "editor-filter-verify" to { check(io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.editorRenderSystem.debugFilter == io.github.some_example_name.render.DebugFilter.ENERGY) }
        steps += "editor-tools-symmetry" to { if(allActors().any { it.name == "editor-tools" }) click("editor-tools") }
        steps += "editor-symmetry" to { click("button.symmetry") }
        route("GoGenomeEditor")
        steps += "editor-tools-save" to { if(allActors().any { it.name == "editor-tools" }) click("editor-tools") }
        steps += "editor-save" to { click("button.saveGenome") }
        route("GoGuide")
        steps += "guide-cells" to { replay(GuideIntent.SelectTab(GuideTab.CELLS)) }; bottom("cells-bottom")
        steps += "guide-formulas" to { replay(GuideIntent.SelectTab(GuideTab.FORMULAS)) }; bottom("formulas-bottom")
        listOf("eye","synapse","neuron","delay","pheromone_sensor").forEach { key ->
            steps += "lab-$key" to { replay(GuideIntent.CloseCellLab); replay(GuideIntent.OpenCellLab(key)) }
            bottom("lab-$key-bottom")
        }
        route("GoSimulation")
        steps += "simulation-filter" to { click("debug-filter-button") }
        steps += "simulation-signal" to { click("debugFilter.signal") }
        steps += "simulation-filter-verify" to { check(io.github.some_example_name.old.core.DISimulationContainer.renderSystem.debugFilter == io.github.some_example_name.render.DebugFilter.SIGNAL) }
        steps += "simulation-menu" to {
            Gdx.input.inputProcessor.touchDown((40*d).toInt(),(40*d).toInt(),0,Input.Buttons.LEFT)
            Gdx.input.inputProcessor.touchUp((40*d).toInt(),(40*d).toInt(),0,Input.Buttons.LEFT)
        }
        bottom("simulation-menu-bottom")
        route("GoEcoSystem")
        steps += "ecosystem-disabled" to {
            for (key in listOf("eco.global","eco.cells")) {
                check(allActors().filterIsInstance<TextButton>().single { it.text.toString() == DIGameGlobalContainer.bundle.get(key) }.isDisabled)
            }
        }
        route("GoMenu")
        if (!java.lang.Boolean.getBoolean("audit.rotate")) steps += "resize-landscape" to { Gdx.graphics.setWindowedMode(h,w) }
        if (!java.lang.Boolean.getBoolean("audit.rotate")) steps += "resize-portrait" to { Gdx.graphics.setWindowedMode(w,h) }
        steps += "bug-report" to { io.github.some_example_name.old.bug.BugOverlay.requestReport() }
    }
    val rotate = java.lang.Boolean.getBoolean("audit.rotate")
    var rotatingScreen: Screen? = null
    lateinit var auditGraphics: Graphics
    Lwjgl3Application(object: ApplicationListener by game {
        override fun create() {
            val actual = Gdx.graphics
            Gdx.graphics = Proxy.newProxyInstance(Graphics::class.java.classLoader,arrayOf(Graphics::class.java)) { _,method,args ->
                if(method.name == "getDensity") d else method.invoke(actual,*(args ?: emptyArray()))
            } as Graphics
            if(System.getProperty("audit.android") == "true") {
                val application = Gdx.app
                Gdx.app = Proxy.newProxyInstance(Application::class.java.classLoader,arrayOf(Application::class.java)) { _,method,args ->
                    if(method.name == "getType") Application.ApplicationType.Android else method.invoke(application,*(args ?: emptyArray()))
                } as Application
            }
            auditGraphics = Gdx.graphics
            game.create()
            File(out,"gpu.txt").writeText(Gdx.gl.glGetString(com.badlogic.gdx.graphics.GL20.GL_RENDERER))
        }
        override fun resize(width: Int, height: Int) {
            Gdx.graphics = auditGraphics
            game.resize(width,height)
        }
        override fun render() {
            Gdx.graphics = auditGraphics
            try {
                game.render()
                frame++
                if(index == -1 && frame == 6) {
                    capture("first-launch")
                    fun dismiss(a: Actor) {
                        if(a is TextButton && a.text.toString() == DIGameGlobalContainer.bundle.get("bug.hint.close")) {
                            val pos = a.localToStageCoordinates(Vector2(a.width/2,a.height/2))
                            a.stage.stageToScreenCoordinates(pos)
                            Gdx.input.inputProcessor.touchDown(pos.x.toInt(),pos.y.toInt(),0,Input.Buttons.LEFT)
                            Gdx.input.inputProcessor.touchUp(pos.x.toInt(),pos.y.toInt(),0,Input.Buttons.LEFT)
                        } else if(a is Group) a.children.toList().forEach { dismiss(it) }
                    }
                    stages(Gdx.input.inputProcessor).forEach { dismiss(it.root) }
                }
                if (rotate && index >= 0 && !failedStep) {
                    if (frame == 4) {
                        rotatingScreen = game.screen
                        Gdx.graphics.setWindowedMode(h,w)
                    }
                    if (frame == 8) {
                        check(game.screen === rotatingScreen && Gdx.graphics.width == h && Gdx.graphics.height == w)
                        capture(steps[index].first + "-rotated")
                        Gdx.graphics.setWindowedMode(w,h)
                    }
                    if (frame == 12) check(game.screen === rotatingScreen && Gdx.graphics.width == w && Gdx.graphics.height == h)
                }
                if(frame == 12) {
                    if(index >= 0 && !failedStep) capture(steps[index].first)
                    index++
                    if(index >= steps.size) { Gdx.app.exit(); return }
                    failedStep = false
                    try { steps[index].second() } catch(e: Throwable) {
                        failed = true; failedStep = true
                        File(out,"step-failures.txt").appendText(steps[index].first+"\n"+e.stackTraceToString()+"\n")
                        nav("GoMenu")
                    }
                    frame=0
                }
            } catch(e: Throwable) {
                File(out,"failure.txt").writeText(e.stackTraceToString()); failed=true; Gdx.app.exit()
            }
        }
    },config)
    exitProcess(if(failed) 1 else 0)
}
