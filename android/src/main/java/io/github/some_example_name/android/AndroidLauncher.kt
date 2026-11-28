package io.github.some_example_name.android

import android.content.Intent
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.app.Activity
import android.annotation.TargetApi
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import games.spooky.gdx.nativefilechooser.android.AndroidFileChooser
import io.github.some_example_name.old.core.PlatformTuning
import io.github.some_example_name.old.bug.BugOverlay
import io.github.some_example_name.old.game.KeyBoardListener
import io.github.some_example_name.old.game.MyGame
import java.io.File

//class AndroidLauncher : AndroidApplication() {
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//
//        val config = AndroidApplicationConfiguration().apply {
//            useImmersiveMode = true
//            useGL30 = true // Enable GLES 3.0+
//        }
//
//        initialize(MyGame { ShaderManagerAndroidApi() }, config)
//    }
//}


open class AndroidLauncher : AndroidApplication(), KeyBoardListener {

    private lateinit var game: MyGame
    private val pendingResults = HashMap<Int, (Int, Intent?) -> Unit>()
    private var nextRequestCode = 20_000

    fun startForResult(intent: Intent, callback: (Int, Intent?) -> Unit) {
        val code = nextRequestCode++
        pendingResults[code] = callback
        @Suppress("DEPRECATION")
        startActivityForResult(intent, code)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        pendingResults.remove(requestCode)?.invoke(resultCode, data)
    }

    private var stopScreenshotDetection: (() -> Unit)? = null

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= 34) stopScreenshotDetection = listenForScreenshots()
    }

    @TargetApi(34)
    private fun listenForScreenshots(): () -> Unit {
        val callback = Activity.ScreenCaptureCallback { postRunnable { BugOverlay.onScreenshotDetected() } }
        registerScreenCaptureCallback(mainExecutor, callback)
        return { unregisterScreenCaptureCallback(callback) }
    }

    override fun onStop() {
        stopScreenshotDetection?.invoke()
        stopScreenshotDetection = null
        super.onStop()
    }

    companion object {
        var inputCallback: ((Float) -> Unit)? = null
        lateinit var inputLayout: LinearLayout
        lateinit var editText: EditText
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configureSimulationThreads()

        val config = AndroidApplicationConfiguration().apply {
            useGyroscope = true
            useImmersiveMode = true
            useGL30 = true          // Контекст OpenGL ES 3.0; compute не требуется.

        }

        val fileProvider = AndroidFileProvider(this, AndroidFileChooser(this))
        game = MyGame(fileProvider)
        val gameView = initializeForView(
            wrapGame(game),
            config
        )

        val rootLayout = FrameLayout(this)
        rootLayout.addView(gameView)

        // ====================== UI ДЛЯ ВВОДА ЧИСЕЛ ======================
        editText = EditText(this).apply {
            hint = "Введите число"
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.BLACK)
            textSize = 20f
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    applyInput()
                    true
                } else false
            }
        }

        val button = Button(this).apply {
            text = "Ok"
            setOnClickListener { applyInput() }
        }

        inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xAA000000.toInt())
            addView(editText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            visibility = View.GONE
        }

        rootLayout.addView(inputLayout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))

        setContentView(rootLayout)
        openMapIntent(intent)

        // Смещение при открытии клавиатуры
        rootLayout.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            rootLayout.getWindowVisibleDisplayFrame(r)
            val screenHeight = rootLayout.rootView.height
            val keypadHeight = screenHeight - r.bottom
            inputLayout.translationY = if (keypadHeight > screenHeight * 0.15) -keypadHeight.toFloat() else 0f
        }
    }

    /**
     * Чем libGDX зовёт игру. В игре — сама игра; стенд замера на телефоне (сборка perf,
     * PhonePerfLauncher) оборачивает её своим сценарием, как PerfCheck на ПК.
     */
    protected open fun wrapGame(game: MyGame): com.badlogic.gdx.ApplicationListener = game

    /**
     * Настройка пула воркеров симуляции под конкретное устройство. Обязана отработать до
     * initializeForView: контейнер симуляции читает эти значения при создании пула.
     *
     * Одно ядро сознательно оставляется свободным: воркер 0 — это поток симуляции, но
     * рядом крутится GL-поток, и если занять спином все ядра, планировщик начнёт вытеснять
     * поток симуляции посреди стадии, пока остальные ждут его на барьере. На спин-барьере
     * такая ситуация стоит дороже, чем потерянное ядро.
     *
     * Приоритет: THREAD_PRIORITY_URGENT_DISPLAY (-8) — тот же класс, в котором работает
     * поток рендера. Здесь важна не сама величина, а то, что у потока симуляции и у всех
     * воркеров она одинаковая: разъехавшиеся приоритеты дают инверсию на барьере, когда
     * ждущий поток отбирает такты у того, кого ждёт.
     */
    protected open fun configureSimulationThreads() {
        PlatformTuning.performanceCoreCount = maxOf(2, AndroidCpu.performanceCoreCount() - 1)
        PlatformTuning.onWorkerThreadStart = {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        }
        // Физика на Rust (RUST_PORT.md «Rust в игре»): на телефоне Rust быстрее Kotlin в 1.4–1.7 раза при тех же битах.
        // Нет библиотеки arm64-v8a в APK (32-битный телефон, сборка без Rust) — Kotlin. Потоки пула Rust — с тем же
        // приоритетом, что воркеры игры выше: поток симуляции — его воркер 0.
        PlatformTuning.rustPhysics = true
        PlatformTuning.rustWorkerNice = android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
    }

    // ====================== KeyBoardListener ======================
    override fun showNativeInput(default: String, callback: (Float) -> Unit) {
        runOnUiThread {
            editText.setText(default)
            editText.setSelection(editText.text.length)
            editText.inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            editText.requestFocus()
            inputCallback = callback
            inputLayout.visibility = View.VISIBLE

            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun applyInput() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editText.windowToken, 0)
        inputLayout.visibility = View.GONE

        val value = editText.text.toString().toFloatOrNull()
        if (value != null) {
            inputCallback?.invoke(value)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openMapIntent(intent)
    }

    private fun openMapIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        // URI может быть content:// с непрозрачным именем: формат проверяет core.
        // Копирование — вне GL-потока; временный файл удаляем после снимка для журнала.
        Thread({
            val temporary = File.createTempFile("opened-", ".map", cacheDir)
            try {
                val input = contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("не удалось открыть $uri")
                input.use { source -> temporary.outputStream().use { source.copyTo(it) } }
                postRunnable {
                    try {
                        game.openFiles(listOf(temporary.absolutePath))
                    } finally {
                        temporary.delete()
                    }
                }
            } catch (e: Exception) {
                temporary.delete()
                Log.e("MapImport", "не удалось прочитать $uri", e)
            }
        }, "map-import").start()
    }
}
