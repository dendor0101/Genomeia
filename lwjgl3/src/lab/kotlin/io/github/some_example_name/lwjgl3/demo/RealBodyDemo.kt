package io.github.some_example_name.lwjgl3.demo

import io.github.some_example_name.lwjgl3.StartupHelper
import io.github.some_example_name.physics.lab.window.launchRealBodyDemo

/** Платформенный запуск; окно, сценарии и повтор принадлежат :physics-lab. */
fun main(args: Array<String>) {
    if (StartupHelper.startNewJvmIfRequired()) return
    launchRealBodyDemo(args)
}
