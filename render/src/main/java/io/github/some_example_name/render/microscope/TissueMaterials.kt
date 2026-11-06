package io.github.some_example_name.render.microscope

/** Ключи материалов — контракт GPU, не зависимость :render от классов симуляции. */
object TissueMaterials {
    private val names = arrayOf("leaf", "fat", "bone", "tail", "neuron", "muscle", "foodsensor",
        "sucker", "stem", "excreta", "suctioncup", "sticky", "pumper", "chameleon", "eye",
        "compass", "synapse", "touchtrigger", "zygote", "producer", "breakaway", "fin",
        "pheromoneemitter", "pheromonesensor", "punisher", "speedsensor", "controller")
    fun forKey(key: String): Int {
        // Delay — нейронная ткань: использует тот же материал без нового слоя атласа.
        val name = key.replace("_", "").lowercase()
        val i = names.indexOf(if (name == "delay") "neuron" else name)
        return if (i < 0 || i == 6 || i == 15 || i == 20 || i == 21) 27 else i
    }
}
