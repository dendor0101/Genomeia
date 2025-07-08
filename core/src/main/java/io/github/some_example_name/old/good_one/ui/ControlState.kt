package io.github.some_example_name.old.good_one.ui


data class UiResult(
    var id: String? = null,
    var cellType: Int? = null,
    var angle: Float? = null,
    var funActivation: Int? = null,
    var a: Float? = null,
    var b: Float? = null,
    var c: Float? = null,
)

sealed class DivideEvent
