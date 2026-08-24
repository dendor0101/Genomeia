package io.github.some_example_name.old.commands

sealed interface PlayerCommand {
    /** Стабильное имя для журнала действий: минификатор его не тронет. */
    val name: String

    /** Короткая сводка параметров. Координаты округляются: доли клетки в логе не нужны. */
    val detail: String get() = ""

    data class TouchDown(val x: Float, val y: Float, val isLeftButton: Boolean) : PlayerCommand {
        override val name get() = "TouchDown"
        override val detail get() = "${x.toInt()},${y.toInt()} ${if (isLeftButton) "L" else "R"}"
    }

    data class Drag(val x: Float, val y: Float, val dx: Float, val dy: Float) : PlayerCommand {
        override val name get() = "Drag"
        override val detail get() = "${x.toInt()},${y.toInt()}"
    }

    class Tap(
        val x: Float,
        val y: Float,
        val isLeftButton: Boolean = true,
        val genomeIndex: Int? = null
    ) : PlayerCommand {
        override val name get() = "Tap"
        override val detail
            get() = "${x.toInt()},${y.toInt()} ${if (isLeftButton) "L" else "R"} genome=${genomeIndex ?: "current"}"
    }

    object StopDrag : PlayerCommand {
        override val name get() = "StopDrag"
    }
}
