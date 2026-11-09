package io.github.some_example_name.render.microscope

/** Строгие преобразования исходных GLSL выполняются один раз при компиляции. */
internal object OpticalFusionShaders {
    private fun String.patch(old:String,new:String):String {
        check(contains(old)) { "HTML fusion source changed: $old" };return replace(old,new)
    }
    fun compose(source:String):String = source.patch("texture(uBehind,","pigmentedBackground(")
        .patch("void main(){", """
            uniform sampler2D uPigment;
            vec4 pigmentedBackground(vec2 uv){
                vec4 bg=texture(uBehind,uv);
                vec3 tr=exp(-max(texture(uPigment,uv).rgb,vec3(0.))*uDarkContrast);
                return vec4(bg.rgb*tr,bg.a*dot(tr,vec3(.2126,.7152,.0722)));
            }
            void main(){
        """.trimIndent())
}
