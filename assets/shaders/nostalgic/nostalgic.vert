#version 300 es
precision highp float;

// Клетка ностальгического режима.
//
// Общий вершинный шейдер для обоих проходов NostalgicRenderer по геометрии — заливки и
// подсчёта контура. Глубину здесь НЕ задаём: её точно считает фрагментный шейдер (см.
// nostalgic.frag), а геометрия — лишь грубый корпус вокруг круга, восьмиугольник из
// CellGeometry. Отсюда и разница с circle_pc.vert, где глубина интерполируется по
// многоугольному конусу.
//
// Свой шейдер, а не circle_pc.vert, ещё и потому, что тому нужны поворот текстуры и
// рефракция: три hash() на sin() и пара cos/sin на каждую вершину. Здесь из этого не
// нужно ничего, а раскладка входов та же — VAO у обоих общий (CellGeometry).
layout(location = 0) in vec3 a_corner;  // xy — точка корпуса в долях радиуса; z не нужен

// Инстансные атрибуты, раскладка — в CellInstanceBuffer.
layout(location = 1) in vec2 a_center;
layout(location = 2) in vec4 a_color;   // r, g, b, a
layout(location = 3) in vec4 a_shape;   // —, —, радиус, энергия
layout(location = 4) in vec4 a_type;    // тип клетки, ключ шума lo, hi, флаги
layout(location = 7) in vec2 a_prevCenter;  // центр в прошлом тике

uniform mat4 u_projTrans;

// Интерполяция между тиками — см. CellInstanceBuffer: клетка рисуется на долю u_lag назад
// от снимка, между нынешней позой и прошлой (a_prevCenter, a_prevDir). u_lag = 0 — ровно
// снимок: так рисуют редактор, превью и стенд (у них прошлая поза равна нынешней).
// Круг поворота не замечает — здесь нужен только центр.
uniform float u_lag;

/**
 * Радиус ядра в долях радиуса клетки при энергии ENERGY_SPAN (байт энергии = 255).
 * Ядро растёт с энергией линейно, см. NostalgicRenderer.NUCLEUS_AT_FULL_ENERGY.
 */
uniform float u_nucleusAtFullEnergy;

/**
 * Точка в долях радиуса клетки: (0, 0) в центре, длина 1 на окружности.
 *
 * Позиция интерполируется растеризатором ТОЧНО — она аффинная по треугольнику, в отличие
 * от расстояния. Поэтому length(ex_Local) во фрагменте даёт точное d/R, а не многоугольное
 * приближение конуса.
 */
out vec2 ex_Local;

flat out vec3 ex_Color;

/** Радиус ядра в долях R; отрицательный — ядра нет (частица не клетка). */
flat out float ex_Nucleus;

// CellInstanceBuffer.FLAG_NOT_CELL в младшем бите байта флагов.
const float FLAG_NOT_CELL = 1.0;

void main() {
    // Распаковка радиуса — та же, что в circle_pc.vert: RADIUS_MIN + байт * RADIUS_SPAN.
    float radius = 0.05 + a_shape.z * 0.7;

    ex_Local = a_corner.xy;
    ex_Color = a_color.rgb;

    // Байт флагов обратно в целое — с округлением, как byteValue() в circle_pc.vert.
    float flags = floor(a_type.w * 255.0 + 0.5);
    bool isCell = mod(flags, 2.0) < FLAG_NOT_CELL;

    // a_shape.w — энергия / ENERGY_SPAN, то есть 0..1. Без энергии ядра нет вовсе.
    ex_Nucleus = isCell ? a_shape.w * u_nucleusAtFullEnergy : -1.0;

    vec2 center = mix(a_center, a_prevCenter, u_lag);
    gl_Position = u_projTrans * vec4(a_corner.xy * radius + center, 0.0, 1.0);
}
