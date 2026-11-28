package io.github.some_example_name.old.core

import com.badlogic.gdx.files.FileHandle
import java.io.File

interface FileProvider {
    fun getGenomeFile(fileName: String): File

    fun exportGenome(fileName: String)

    /**
     * Выбрать файлы геномов — и .genome, и старые .json: системное окно выбора. [callback]
     * получает выбранные файлы (пустой список — отменили или платформа не умеет) и может
     * прийти в любом потоке. Файлы только читаются: в папку геномов их кладёт игра
     * (GenomeImport), одинаково на всех платформах.
     */
    fun pickGenomeFiles(callback: (List<FileHandle>) -> Unit) = callback(emptyList())

    /** Умеет ли платформа [pickGenomeFiles]: нет — кнопки импорта нет. */
    val canPickGenomeFiles: Boolean get() = false

    fun getExternalFilesDir(type: String?): File?

    /**
     * Отдать файл игроку: на телефоне — системное окно «поделиться» (почта, мессенджер),
     * на ПК — открыть папку с файлом. false — не вышло (или платформа не умеет).
     */
    fun shareFile(file: File, title: String): Boolean = false

    /**
     * Картинка из буфера обмена — байты файла (PNG, JPEG...) и расширение без точки,
     * или null: в буфере не картинка, или платформа так не умеет. Для отчёта о баге.
     */
    fun clipboardImage(): Pair<ByteArray, String>? = null

    /**
     * Выбрать картинку: галерея на телефоне, файл на ПК. [callback] получает байты и имя
     * файла (null — отменили или не вышло) и может прийти в любом потоке.
     */
    fun pickImage(callback: (bytes: ByteArray?, name: String?) -> Unit) = callback(null, null)

    /**
     * Выбрать текстовый файл и прочитать его как UTF-8. [extensions] — расширения без точки
     * («lua»); пусто — любой файл. [callback] получает имя и содержимое (null — отменили или
     * платформа не умеет) и может прийти в любом потоке, поэтому UI обновлять через
     * Gdx.app.postRunnable.
     */
    fun pickTextFile(
        extensions: List<String> = emptyList(),
        callback: (name: String?, content: String?) -> Unit
    ) = callback(null, null)

    /** Умеет ли платформа [pickTextFile]: нет — кнопку загрузки лучше не показывать. */
    val canPickTextFile: Boolean get() = false

    /**
     * Сохранить текст в файл, выбранный игроком: на ПК — системное окно «Сохранить как», на
     * Android — SAF (ACTION_CREATE_DOCUMENT), в браузере — скачивание. [suggestedName]
     * подставляется в диалог (с расширением). [callback] получает true при успехе и может
     * прийти в любом потоке, поэтому UI обновлять через Gdx.app.postRunnable.
     */
    fun saveTextFile(
        suggestedName: String,
        content: String,
        callback: (saved: Boolean, name: String) -> Unit = {_, _, ->}
    ) = callback(false, "")

    /** Умеет ли платформа [saveTextFile]: нет — кнопку сохранения лучше не показывать. */
    val canSaveTextFile: Boolean get() = false

    /**
     * Сведения об устройстве для отчёта о баге: модель, версия системы и т. п. — то, по
     * чему баги группируются («падает только на таком-то телефоне»). Без личных данных.
     */
    fun deviceInfo(): Map<String, String> = emptyMap()
}
