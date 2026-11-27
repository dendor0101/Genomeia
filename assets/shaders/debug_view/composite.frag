#version 300 es
precision mediump float;

// Проход 2 отладочного режима: заливка клеток из FBO — на экран, поверх фона.
//
// Цвет в FBO премножен на покрытие, поэтому сглаженный край тела ложится на фон без
// ореола. Фон рисуется здесь же, а не очисткой экрана: он часть картинки режима.
// texelFetch, а не texture: FBO ровно в размер экрана, и фильтрация тут не нужна.
//
// u_backgroundAlpha = 0 — снимок без фона (значок клетки): пустота прозрачна, и в буфере
// остаётся премноженный цвет клеток с их покрытием в альфе. Следующие проходы смешивают
// (ONE, ONE_MINUS_SRC_ALPHA) — это и есть «поверх» для премноженного цвета, альфа копится верно.
// При 1 — прежний кадр: умножение на 1 и mix(a, 1, 1) точны.

uniform sampler2D u_texture;
uniform vec3 u_background;
uniform float u_backgroundAlpha;

out vec4 fragColor;

void main() {
    vec4 cells = texelFetch(u_texture, ivec2(gl_FragCoord.xy), 0);
    fragColor = vec4(u_background * (1.0 - cells.a) * u_backgroundAlpha + cells.rgb, mix(cells.a, 1.0, u_backgroundAlpha));
}
