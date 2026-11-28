package io.github.some_example_name.old.bug

/**
 * meta.json — краткое описание .bug для сервера: по нему отчёты сортируются и
 * группируются, не открывая сам файл. Лежит внутри .bug и уходит отдельной частью
 * запроса при отправке (BugUploader).
 *
 * Собирается из манифеста, поэтому всегда с ним согласован: поменялось описание или
 * картинки — переписывается и манифест, и meta.json.
 *
 * Главное для группировки:
 *  - crash.signature — хэш класса исключения и пяти верхних кадров его первопричины, без
 *    номеров строк: одинаковые краши из одной версии дают одну подпись;
 *  - game, platform.type, device.* — «падает только на Android 9», «только на Mali».
 */
object BugMeta {

    /** Версия схемы JSON — растёт, если меняется смысл полей. */
    const val SCHEMA = 1

    fun json(manifest: Map<String, String>, fileName: String, fileSize: Long?): String {
        val json = Json()
        json.obj {
            num("schema", SCHEMA)
            str("file", fileName)
            fileSize?.let { num("size", it) }
            str("kind", manifest["kind"])
            str("game", manifest["game"])
            // Отчёты до 21.11 коммита не знают — у них поля нет (str пропускает null).
            if (manifest["build.commit"] != null) {
                obj("build") {
                    str("commit", manifest["build.commit"])
                    str("branch", manifest["build.branch"])
                    manifest["build.dirty"]?.let { bool("dirty", it == "true") }
                }
            }
            str("created", manifest["created"])
            str("deviceId", manifest["deviceId"])

            obj("platform") {
                str("type", manifest["platform"])
                str("version", manifest["platformVersion"])
                str("os", manifest["os"])
                str("java", manifest["java"])
                // Движок физики (rust/kotlin); у отчётов до 27.11 поля нет — str его пропускает.
                str("physics", manifest["physics.engine"])
            }

            obj("device") {
                manifest.filterKeys { it.startsWith(DEVICE_PREFIX) }.forEach { (key, value) ->
                    str(key.removePrefix(DEVICE_PREFIX), value)
                }
                str("gpu", manifest["gpu"])
                str("gpuVendor", manifest["gpu.vendor"])
                str("gl", manifest["gl"])
                str("screen", manifest["screen"])
                str("density", manifest["density"])
                str("locale", manifest["locale"])
            }

            if (manifest["crash.exception"] != null) {
                obj("crash") {
                    str("exception", manifest["crash.exception"])
                    str("message", manifest["crash.message"])
                    str("rootCause", manifest["crash.rootCause"])
                    str("thread", manifest["crash.thread"])
                    str("signature", manifest["crash.signature"])
                    arr("top", manifest["crash.top"]?.split(" | ").orEmpty())
                }
            } else {
                nul("crash")
            }

            obj("session") {
                str("started", manifest["session.started"])
                str("route", manifest["route"])
                manifest["final.epoch"]?.toLongOrNull()?.let { num("epoch", it) }
                manifest["final.tick"]?.toLongOrNull()?.let { num("tick", it) }
            }

            obj("journal") {
                manifest["journal.ui"]?.toLongOrNull()?.let { num("ui", it) }
                manifest["journal.sim"]?.toLongOrNull()?.let { num("sim", it) }
                str("uiHash", manifest["journal.uiHash"])
                str("simHash", manifest["journal.simHash"])
            }

            obj("report") {
                str("description", manifest["description"])
                num("images", manifest["images"]?.toLongOrNull() ?: 0)
                bool("screenshot", manifest["screenshot"] == "true")
            }
        }
        return json.toString()
    }

    const val DEVICE_PREFIX = "device."

    /** Минимальный писатель JSON: зависимость ради одного файла не нужна. */
    private class Json {
        private val out = StringBuilder()
        private var needComma = false

        fun obj(block: Json.() -> Unit) {
            out.append('{')
            needComma = false
            block()
            out.append('}')
            needComma = true
        }

        fun obj(name: String, block: Json.() -> Unit) {
            key(name)
            out.append('{')
            needComma = false
            block()
            out.append('}')
            needComma = true
        }

        fun str(name: String, value: String?) {
            if (value == null) return
            key(name)
            quote(value)
            needComma = true
        }

        fun num(name: String, value: Number) {
            key(name)
            out.append(value)
            needComma = true
        }

        fun bool(name: String, value: Boolean) {
            key(name)
            out.append(value)
            needComma = true
        }

        fun nul(name: String) {
            key(name)
            out.append("null")
            needComma = true
        }

        fun arr(name: String, values: List<String>) {
            key(name)
            out.append('[')
            values.forEachIndexed { i, v ->
                if (i > 0) out.append(',')
                quote(v)
            }
            out.append(']')
            needComma = true
        }

        private fun key(name: String) {
            if (needComma) out.append(',')
            quote(name)
            out.append(':')
        }

        private fun quote(value: String) {
            out.append('"')
            for (c in value) {
                when {
                    c == '"' -> out.append("\\\"")
                    c == '\\' -> out.append("\\\\")
                    c == '\n' -> out.append("\\n")
                    c == '\r' -> out.append("\\r")
                    c == '\t' -> out.append("\\t")
                    c < ' ' -> out.append(String.format("\\u%04x", c.code))
                    else -> out.append(c)
                }
            }
            out.append('"')
        }

        override fun toString(): String = out.toString()
    }
}
