#ifdef GL_ES
precision mediump float;
#endif

varying vec2 v_texCoord;

void main() {
    // Просто возвращаем фиксированный цвет
    gl_FragColor = vec4(0.5, 0.5, 1.0, 1.0); // Рисуем синий цвет
}
