package io.github.some_example_name.old.features.guide.celllab

/** Здесь только сценарии и диапазоны управления; поведение выполняют игровые Cell. */
internal object CellLabCatalog {
    val keys = listOf("leaf", "fat", "bone", "tail", "controller", "muscle", "sucker", "excreta",
        "punisher", "pumper", "zygote", "producer", "neuron", "suction_cup", "sticky", "chameleon",
        "eye", "touch_trigger", "synapse", "pheromone_emitter", "pheromone_sensor", "delay")

    data class Control(val key: String, val min: Float, val max: Float, val step: Float, val initial: Float)
    private fun signal(initial: Float = 0f) = Control("signal", -1f, 1f, 0.01f, initial)
    private val angle = Control("angle", -180f, 180f, 1f, 0f)
    private val light = Control("light", 0f, 0.12f, 0.001f, 0.06f)
    private val channels = Control("channels", 0f, 7f, 1f, 7f)
    private val feed = Control("feed", 0f, 1f, 1f, 1f)
    fun controls(key: String): List<Control> = when (key) {
        "leaf", "fat", "zygote" -> listOf(light)
        "bone" -> emptyList()
        "tail" -> listOf(signal(), angle, light)
        "controller" -> listOf(Control("key", 0f, 3f, 1f, 0f), Control("pressed", 0f, 1f, 1f, 0f))
        "muscle", "suction_cup", "sticky", "excreta" -> listOf(signal())
        "sucker" -> emptyList()
        "punisher" -> listOf(feed, Control("prey", 0f, 1f, 1f, 0f), light)
        "pumper" -> emptyList()
        "producer" -> listOf(signal(), light)
        "neuron" -> listOf(signal(), Control("formula", 0f, 3f, 1f, 0f),
            Control("a", -3f, 3f, 0.05f, 1f), Control("b", -2f, 2f, 0.05f, 0f))
        "chameleon" -> listOf(signal(), channels)
        "eye" -> listOf(channels, angle, Control("range", 1f, 6f, 0.1f, 4f))
        "touch_trigger" -> emptyList()
        "synapse" -> listOf(Control("delay", -30f, 30f, 1f, 8f),
            Control("learning", 0.01f, 0.3f, 0.01f, 0.1f), Control("depression", 0.01f, 0.3f, 0.01f, 0.1f),
            Control("decay", 0.01f, 0.3f, 0.01f, 0.05f))
        // Источник шлёт волну (импульсы или синус), Delay повторяет её с отставанием.
        "delay" -> listOf(Control("wave", 0f, 1f, 1f, 0f), Control("ticks", 0f, 60f, 1f, 15f),
            Control("binary", 0f, 1f, 1f, 0f))
        "pheromone_emitter", "pheromone_sensor" -> listOf(signal(1f),
            Control("emitterType", 0f, 3f, 1f, 0f), Control("sensorType", 0f, 3f, 1f, 0f))
        else -> emptyList()
    }
}
