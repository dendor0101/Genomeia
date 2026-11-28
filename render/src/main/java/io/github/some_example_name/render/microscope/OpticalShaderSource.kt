package io.github.some_example_name.render.microscope

/** Варианты материала берут уже подготовленный vertex исходник. Повторная
 * подготовка не должна дублировать layout: desktop это допускает, ES 3.00 — нет. */
internal object OpticalShaderSource {
    fun attributes(source:String):String {
        var s=source.replace("in vec4 aI0,aI1,aI2;","in vec4 aI0;in vec4 aI1;in vec4 aI2;")
        for((i,name) in arrayOf("aCorner","aI0","aI1","aI2").withIndex()){
            val declaration="in ${if(i==0)"vec2" else "vec4"} $name;"
            s=Regex("(?:layout\\s*\\([^)]*\\)\\s*)*"+Regex.escape(declaration))
                .replace(s,"layout(location=$i) $declaration")
        }
        return s
    }
}
