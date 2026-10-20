#version 300 es
precision highp float;

// Клетка отладочного режима. Общий вершинный шейдер для обоих проходов по клеткам
// DebugRenderer: заливки (cell_fill.frag) и сглаживания границ (cell_edge.frag).
//
// Геометрия — описанный восьмиугольник из CellGeometry (Detail.HULL), раздутый на
// AA_MARGIN_PX пикселей: второму проходу нужны фрагменты чуть ЗА окружностью, чтобы
// размыть край клетки наружу. В проходе сглаживания корпус раздут ещё и на бахрому
// рисунка (реснички, иглы — fringeLength). Точное d/R считает фрагментный шейдер по
// v_local — позиция интерполируется растеризатором точно, в отличие от расстояния.

#include "cell_look.glsl"

layout(location = 0) in vec3 a_corner;  // xy — точка корпуса в долях радиуса

// Инстансные атрибуты, раскладка — в CellInstanceBuffer.
layout(location = 1) in vec2 a_center;
layout(location = 2) in vec4 a_color;   // r, g, b, a
layout(location = 3) in vec4 a_shape;   // cos, sin, радиус, энергия
layout(location = 4) in vec4 a_type;    // тип клетки, ключ шума lo, hi, флаги
layout(location = 5) in vec4 a_look;    // рисунок, сигнал действия, прозрачность, —

uniform mat4 u_projTrans;

/** Сколько единиц мира приходится на один пиксель экрана. */
uniform float u_pixelWorld;

/** 1 — раздуть корпус на бахрому рисунка (проход сглаживания), 0 — нет (заливка). */
uniform float u_fringe;

/** Фильтр отладки — DebugFilter.ordinal: 0 оригинал, 1 энергия, 2 нейросигнал. */
uniform int u_filter;

const int FILTER_ENERGY = 1;
const int FILTER_SIGNAL = 2;

/** На сколько пикселей корпус выходит за окружность: полоса сглаживания края. */
const float AA_MARGIN_PX = 1.5;

/** Точка в долях радиуса клетки: длина 1 — окружность. */
out vec2 v_local;

flat out vec3 v_color;

/** Шаг d/R на один пиксель экрана: перевод «долей радиуса» в пиксели. */
flat out float v_tPerPixel;

/** Направление клетки (cos, sin) — по нему повёрнут рисунок. */
flat out vec2 v_dir;

/** Рисунок — CellLook, P_* в cell_look.glsl. */
flat out int v_pattern;

/** Сигнал действия клетки −1..1: от него анимация мышц, присосок и прочих. */
flat out float v_action;

/** Непрозрачность 0..1; меньше единицы — «призрак», его рисует только проход 3. */
flat out float v_opacity;

/** Ключ клетки 0..65535: от него расположение и фазы её рисунка, см. hash4. */
flat out float v_key;

/**
 * Четыре случайных числа клетки 0..1 — то, что у рисунка одно на клетку (где ядро, сколько
 * отростков, фазы). Здесь, а не во фрагментном шейдере: там это был бы хэш на каждый пиксель.
 */
flat out vec4 v_rand;

/** 1 — клетка закрашена фильтром, рисунок на ней только бледным следом. */
flat out float v_filtered;

float byteValue(float normalized) {
    return floor(normalized * 255.0 + 0.5);
}

/**
 * Цвет клетки под фильтром. Значение — четвёртый байт look (CellLook.filter):
 *  - энергия: заполненность 0..1 — от синего (0, 0, 1) к пурпурному (1, 0, 1);
 *  - нейросигнал: знаковый байт уже нормализованного сигнала (CellLook.signalFilterByte) —
 *    ноль синий, плюс к пурпурному, минус к голубому (0, 1, 1): тормозящий сигнал
 *    отличается от возбуждающего, а молчащая клетка — от обеих.
 */
vec3 filterColor(int kind, float value) {
    if (kind == FILTER_ENERGY) return vec3(value / 255.0, 0.0, 1.0);
    int steps = int(value);
    if (steps > 127) steps -= 256;
    float signal = float(steps) / 127.0;
    return signal >= 0.0 ? vec3(signal, 0.0, 1.0) : vec3(0.0, -signal, 1.0);
}

void main() {
    // Распаковка — та же, что в circle_pc.vert: RADIUS_MIN + байт * RADIUS_SPAN.
    float radius = 0.05 + a_shape.z * 0.7;

    v_tPerPixel = u_pixelWorld / radius;

    int pattern = int(byteValue(a_look.x));
    v_pattern = pattern < P_COUNT ? pattern : P_PLAIN;

    // Знаковый байт. Шаг — целым числом, и только потом в float: выражение (b - 127)/127
    // компилятор вправе переписать через умножение, и на Intel UHD ноль становился -6e-8.
    int action = int(byteValue(a_look.y));
    if (action > 127) action -= 256;
    v_action = float(action) / 127.0;

    v_opacity = 1.0 - byteValue(a_look.z) / 255.0;

    v_key = byteValue(a_type.y) + byteValue(a_type.z) * 256.0;
    v_rand = hash4(vec2(v_key, 0.5));

    // Бахрому рисует только клетка крупнее 10 пикселей (patternFade в cell_common.glsl):
    // мельче корпус не раздуваем — на дальнем плане это были бы тысячи пустых фрагментов.
    float fringe = v_tPerPixel < 0.1 ? u_fringe * fringeLength(v_pattern) : 0.0;
    float grow = 1.0 + AA_MARGIN_PX * v_tPerPixel + fringe;
    v_local = a_corner.xy * grow;

    v_color = a_color.rgb;
    v_filtered = 0.0;
    if (u_filter != 0) {
        // Не-клетка (еда, субстанции) ни энергии, ни сигнала не несёт — гасим её до
        // серого, чтобы не путалась со шкалой фильтра.
        bool isCell = mod(byteValue(a_type.w), 2.0) < 1.0;
        if (isCell) {
            v_color = filterColor(u_filter, byteValue(a_look.w));
            v_filtered = 1.0;
        } else {
            v_color = vec3(dot(a_color.rgb, vec3(0.299, 0.587, 0.114)) * 0.35);
        }
    }
    v_dir = vec2(a_shape.x * 2.0 - 1.0, a_shape.y * 2.0 - 1.0);

    gl_Position = u_projTrans * vec4(v_local * radius + a_center, 0.0, 1.0);
}
