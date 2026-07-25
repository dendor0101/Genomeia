package io.github.some_example_name.android

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.opengl.GLES32
import android.os.Bundle
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import com.example.concurrent.DefaultSimulationSystemFactory
import com.example.concurrent.JvmConcurrentFactory
import games.spooky.gdx.nativefilechooser.android.AndroidFileChooser
import io.github.some_example_name.old.core.concurrent.Platform
import io.github.some_example_name.old.game.KeyBoardListener
import io.github.some_example_name.old.game.MyGame
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter

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


class AndroidLauncher : AndroidApplication(), KeyBoardListener {

    companion object {
        var inputCallback: ((Float) -> Unit)? = null
        lateinit var inputLayout: LinearLayout
        lateinit var editText: EditText
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Crash handler должен быть первым
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))

        super.onCreate(savedInstanceState)
        val config = AndroidApplicationConfiguration().apply {
            useImmersiveMode = true
            useGL30 = true

        }

        val buf = IntArray(1)
        GLES32.glGenBuffers(1, buf, 0)

        val fileProvider = AndroidFileProvider(this, AndroidFileChooser(this))
        Platform.concurrent = JvmConcurrentFactory()
        Platform.simulationFactory = DefaultSimulationSystemFactory()
        val gameView = initializeForView(
            MyGame(fileProvider),
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

        // Смещение при открытии клавиатуры
        rootLayout.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            rootLayout.getWindowVisibleDisplayFrame(r)
            val screenHeight = rootLayout.rootView.height
            val keypadHeight = screenHeight - r.bottom
            inputLayout.translationY = if (keypadHeight > screenHeight * 0.15) -keypadHeight.toFloat() else 0f
        }
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

    private fun copyImportedFile(uri: Uri) {
        val targetFile = File(filesDir, "gen3.bin")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            Toast.makeText(this, "Файл импортирован как gen3.bin", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("AndroidLauncher", "Import failed: ${e.message}")
            Toast.makeText(this, "Ошибка импорта: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ====================== Crash Handler ======================
    private class CrashHandler(private val context: AndroidLauncher) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            val stackTrace = sw.toString()

            Log.e("CrashHandler", stackTrace)

            val crashDir = context.getExternalFilesDir(null)
            crashDir?.mkdirs()
            val crashFile = File(crashDir, "crash_log_android.txt")

            try {
                FileWriter(crashFile).use { it.write(stackTrace) }
            } catch (e: Exception) {
                Log.e("CrashHandler", "Failed to write crash log", e)
            }

            context.runOnUiThread {
                val scrollView = ScrollView(context)
                val textView = TextView(context).apply {
                    text = stackTrace
                    setTextIsSelectable(true)
                    setBackgroundColor(Color.WHITE)
                    setTextColor(Color.BLACK)
                    setPadding(16, 16, 16, 16)
                }
                scrollView.addView(textView)

                AlertDialog.Builder(context)
                    .setTitle("Send the crash log to the community")
                    .setView(scrollView)
                    .setPositiveButton("Share") { dialog, _ ->
                        try {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.provider",
                                crashFile
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Send the crash log"))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                        dialog.dismiss()
                    }
                    .setNegativeButton("Close") { dialog, _ -> dialog.dismiss() }
                    .setOnDismissListener {
                        context.finish()
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }
}
