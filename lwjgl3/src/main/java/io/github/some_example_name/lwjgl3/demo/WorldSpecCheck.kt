package io.github.some_example_name.lwjgl3.demo

import io.github.some_example_name.old.features.worldeditor.WorldEditorIntent
import io.github.some_example_name.old.features.worldeditor.WorldEditorViewModel

/**
 * Регрессионная проверка: совпадает ли карта, собранная по рецепту, с той, что игрок видел
 * в редакторе. Именно на этом равенстве держится передача мира рецептом, а не массивом.
 *
 * Запуск: gradlew :lwjgl3:worldSpecCheck
 */
fun main() {
    val viewModel = WorldEditorViewModel()

    val script = listOf(
        WorldEditorIntent.SetSeed("12345678"),
        WorldEditorIntent.SetDayNight(7),
        WorldEditorIntent.SetSmoothing(3),
        WorldEditorIntent.SetBrushSize(5),
        WorldEditorIntent.Paint(50, 50),
        WorldEditorIntent.Paint(52, 51),
        WorldEditorIntent.SetErasing(true),
        WorldEditorIntent.Paint(60, 60),
        WorldEditorIntent.SetCircleBrush(false),
        WorldEditorIntent.Paint(70, 70),
        WorldEditorIntent.ClearMap,
        WorldEditorIntent.SetErasing(false),
        WorldEditorIntent.Paint(10, 10),
        WorldEditorIntent.SetSmoothing(9),
        WorldEditorIntent.Paint(100, 100)
    )
    script.forEach { viewModel.handle(it) }

    val onScreen = viewModel.map
    val spec = viewModel.toSpec()
    val rebuilt = spec.buildMap()

    var mismatches = 0
    var filled = 0
    for (y in 0 until viewModel.gridHeight) {
        for (x in 0 until viewModel.gridWidth) {
            if (onScreen[y][x]) filled++
            if (onScreen[y][x] != rebuilt[y][x]) mismatches++
        }
    }

    println("Рецепт: $spec")
    println("Заполнено клеток: $filled из ${viewModel.gridWidth * viewModel.gridHeight}")
    println("Расхождений: $mismatches")
    println(if (mismatches == 0) "PASS" else "FAIL")
    if (mismatches != 0) kotlin.system.exitProcess(1)
}
