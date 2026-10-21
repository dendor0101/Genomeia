#version 300 es
precision highp float;

// Оверлей отладочного режима: нейросвязи, стрелки направлений, лучи глаз, кольца
// феромонов. Один инстансный проход, один квад на фигуру — раскладка в
// OverlayInstanceBuffer.
//
// Квад вытянут вдоль отрезка и расширен на полуширину самой широкой части фигуры плюс
// запас под сглаживание. Фрагментному шейдеру отдаются локальные координаты в единицах
// мира: x — вдоль отрезка от его начала, y — поперёк. Всё остальное — расстояния до
// фигуры — считается там.

#include "overlay_common.glsl"

layout(location = 0) in vec2 a_corner;   // x: 0..1 вдоль, y: -1..1 поперёк
layout(location = 1) in vec2 a_from;
layout(location = 2) in vec2 a_to;       // у кольца: x — радиус
layout(location = 3) in vec4 a_color;
layout(location = 4) in vec4 a_params;   // вид, сигнал, ключ фазы, резерв
layout(location = 5) in vec2 a_prevFrom; // начало в прошлом тике
layout(location = 6) in vec2 a_prevTo;   // конец в прошлом тике; у кольца — тот же радиус

uniform mat4 u_projTrans;
uniform float u_pixelWorld;

// Интерполяция между тиками — та же, что у клеток (CellInstanceBuffer): концы фигуры на
// долю u_lag назад, иначе стрелки и связи дёргались бы относительно плавных клеток.
uniform float u_lag;

out vec2 v_local;
flat out float v_length;    // длина отрезка; у кольца — радиус
flat out vec4 v_color;
flat out int v_kind;
flat out float v_signal;    // -1..1
flat out float v_seed;      // 0..1

float byteValue(float normalized) {
    return floor(normalized * 255.0 + 0.5);
}

/** Полуширина корпуса поперёк отрезка, мир. */
float halfExtent(int kind, float pw) {
    float pad = (OUTLINE_PX + 2.0) * pw;
    if (kind == KIND_NEURAL) {
        return max(BEAD_RADIUS * BEAD_HALO, max(CHEVRON_HALF, BEAD_MIN_PX * BEAD_HALO * pw)) + pad;
    }
    if (kind == KIND_ARROW) return HEAD_HALF + pad;
    if (kind == KIND_RAY) return max(RAY_GLOW_HALF * 1.5, RAY_TICK_HALF) + pad;
    return RING_HALF_PX * pw + pad;
}

void main() {
    float pw = u_pixelWorld;

    v_kind = int(byteValue(a_params.x));
    // Сигнал — байт со смещением 127: ноль кодируется точно. Шаг — целым числом, и только
    // потом в float: выражение (b - 127.0) / 127.0 компилятор вправе переписать как
    // b * (1/127) - 1, и на Intel UHD ноль превращался в -6e-8 — по молчащей связи бежали
    // «тормозные» импульсы.
    int steps = int(byteValue(a_params.y)) - 127;
    v_signal = float(steps) / 127.0;
    v_seed = byteValue(a_params.z) / 256.0;
    v_color = a_color;

    float extent = halfExtent(v_kind, pw);
    vec2 world;

    vec2 fromPos = mix(a_from, a_prevFrom, u_lag);
    // У кольца a_to.x — радиус, и прошлый «конец» у него тот же: интерполяция его не трогает.
    vec2 toPos = mix(a_to, a_prevTo, u_lag);

    if (v_kind == KIND_RING) {
        float radius = toPos.x;
        v_length = radius;
        vec2 corner = vec2(a_corner.x * 2.0 - 1.0, a_corner.y) * (radius + extent);
        v_local = corner;
        world = fromPos + corner;
    } else {
        vec2 delta = toPos - fromPos;
        float segment = max(length(delta), 1e-6);
        vec2 dir = delta / segment;
        vec2 normal = vec2(-dir.y, dir.x);

        v_length = segment;
        // Жгутик — единственная фигура, чья ширина зависит от длины: волна и толщина
        // заданы в долях длины.
        if (v_kind == KIND_FLAGELLUM) {
            extent = (FLAGELLUM_AMPLITUDE + FLAGELLUM_HALF) * segment + (OUTLINE_PX + 2.0) * pw;
        }
        float along = mix(-extent, segment + extent, a_corner.x);
        float across = a_corner.y * extent;
        v_local = vec2(along, across);
        world = fromPos + dir * along + normal * across;
    }

    gl_Position = u_projTrans * vec4(world, 0.0, 1.0);
}
