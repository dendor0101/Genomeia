package io.github.some_example_name.physics.lab.session

import io.github.some_example_name.physics.PhysicsConfig
import java.io.Writer
import java.util.Base64

/** Граница с процессом: ядро не читает переменные среды и файлы. */
internal object DemoSettings {
    val defaults: PhysicsConfig by lazy { fromEnvironment() }

    fun fromEnvironment(): PhysicsConfig = PhysicsConfig(System.getenv().filterKeys {
        it.startsWith("RB_") || it.startsWith("CT_")
    })

    fun writeHeader(out: Writer, config: PhysicsConfig) {
        out.write("HDR physicsConfig 1\n")
        for ((key, value) in config.snapshot().toSortedMap()) {
            // Маркер сохраняет и пустое значение: для CT_LEGACY важен сам факт
            // присутствия переменной. Base64 сохраняет пробелы и переводы строк.
            val encoded = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
            out.write("HDR physics.$key b64:$encoded\n")
        }
    }

    fun forReplay(header: Map<String, String>): PhysicsConfig {
        val version = header["physicsConfig"] ?: return fromEnvironment()
        require(version == "1") { "Неизвестная версия параметров физики: $version" }
        val parameters = sortedMapOf<String, String>()
        for ((key, value) in header) if (key.startsWith("physics.")) {
            require(value.startsWith("b64:")) { "Повреждён параметр $key" }
            parameters[key.removePrefix("physics.")] =
                String(Base64.getDecoder().decode(value.removePrefix("b64:")), Charsets.UTF_8)
        }
        // У новой записи свои параметры, в том числе явно пустой набор. Среда
        // машины воспроизведения не должна незаметно менять записанный материал.
        return PhysicsConfig(parameters)
    }
}
