package io.github.some_example_name.old.core

/** Выбор GPU принадлежит ОС. Лаунчер предоставляет только поддерживаемое действие;
 * на телефонах и в вебе пункт отсутствует. Не участвует в состоянии мира. */
object PlatformGraphics {
    var gpuSettings: GpuSettings? = null

    interface GpuSettings {
        val executable: String
        fun open(): Boolean
    }
}
