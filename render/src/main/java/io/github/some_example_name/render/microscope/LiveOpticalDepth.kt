package io.github.some_example_name.render.microscope

/** В HTML наклон стекла задан около (0, 0) маленького препарата. Игровая карта
 * намного больше: глубину считаем от центра столика, чтобы линза, оболочки и
 * взвесь оставались в одной системе Z. Это перенос начала отсчёта, не уплощение
 * купола: рельеф, пять срезов и наклон внутри поля зрения сохраняются.
 * Преобразование только при компиляции; дополнительных проходов/текстур нет. */
internal object LiveOpticalDepth {
    fun source(fragment: String): String {
        var result = fragment
        for (point in arrayOf("p", "wp", "vFiber")) {
            result = result.replace(".5+.0018*$point.x+.0012*$point.y",
                ".5+.0018*($point.x-uCam.x)+.0012*($point.y-uCam.y)")
        }
        // В исходном pigment uCam был только в vertex, теперь нужен и fragment.
        if (result != fragment && !Regex("uniform\\s+vec2[^;]*\\buCam\\b[^;]*;").containsMatchIn(result)) {
            result = result.replace("void main(){", "uniform vec2 uCam;\nvoid main(){")
        }
        return result
    }
}
