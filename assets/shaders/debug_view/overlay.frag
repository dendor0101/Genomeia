#version 300 es
precision highp float;

// Фигуры оверлея отладочного режима — см. overlay.vert.
//
// Каждая фигура описана расстоянием со знаком до своего края (минус — внутри), и
// покрытие пикселя считается прямо из него: clamp(0.5 - d / пиксель). Это аналитическое
// сглаживание — то, что дал бы MSAA, но без лишних выборок и без мультисэмплового
// буфера: одна формула на слой. Слои внутри фигуры складываются «поверх» в премноженном
// виде, а результат смешивается с кадром как GL_ONE, GL_ONE_MINUS_SRC_ALPHA.

#include "overlay_common.glsl"

in vec2 v_local;
flat in float v_length;
flat in vec4 v_color;
flat in int v_kind;
flat in float v_signal;
flat in float v_seed;

uniform float u_pixelWorld;

/** Секунды, по кругу с периодом RenderFrame.TIME_WRAP. Двигает импульсы и жгутики. */
uniform float u_time;

/** Владелец пикселя из заливки клеток (cell_fill.frag): a > 0.25 — пиксель занят клеткой. */
uniform sampler2D u_owner;

out vec4 fragColor;

float cover(float signedDistance) {
    return clamp(0.5 - signedDistance / u_pixelWorld, 0.0, 1.0);
}

/**
 * Модуль сигнала, 0..1. Всё, что меньше половины шага байта (1/127), — тишина: так
 * молчащая связь гарантированно без импульсов, как бы ни округлил компилятор.
 */
float signalMagnitude() {
    float magnitude = min(abs(v_signal), 1.0);
    return magnitude < 0.5 / 127.0 ? 0.0 : magnitude;
}

vec4 paint(vec3 rgb, float alpha) {
    return vec4(rgb * alpha, alpha);
}

vec4 over(vec4 top, vec4 bottom) {
    return top + bottom * (1.0 - top.a);
}

/** Расстояние до отрезка (0,0)–(len,0). */
float segmentDistance(vec2 p, float len) {
    return length(vec2(p.x - clamp(p.x, 0.0, len), p.y));
}

/**
 * Треугольник остриём по +x: основание на x = base полушириной halfWidth, остриё на
 * x = tip. Максимум расстояний до трёх сторон — внутри и на сторонах точный.
 */
float arrowHeadDistance(vec2 p, float base, float tip, float halfWidth) {
    vec2 q = vec2(p.x - tip, abs(p.y));
    vec2 sideNormal = normalize(vec2(halfWidth, tip - base));
    return max(dot(q, sideNormal), base - p.x);
}

/**
 * Нейросвязь от источника (x = 0) к приёмнику (x = длина).
 *
 * Линия цвета связи с тёмной обводкой, шеврон посередине — куда идёт сигнал, и импульсы:
 * бусины бегут от источника к приёмнику. Яркость и размер бусин — от модуля сигнала,
 * который источник отдаёт в связь в этом тике. Положительный — светлая бусина с ореолом,
 * отрицательный (торможение) — тёмная бусина со светлым ободком. Нет сигнала — нет бусин.
 */
vec4 neuralLink(vec2 p) {
    float pw = u_pixelWorld;
    float len = v_length;
    vec3 base = v_color.rgb;
    float strength = sqrt(signalMagnitude());

    // Линия.
    float minHalf = LINK_MIN_HALF_PX * pw;
    float lineHalf = max(LINK_HALF, minHalf);
    float thin = min(1.0, LINK_HALF / minHalf);
    float d = segmentDistance(p, len) - lineHalf;

    vec4 result = paint(vec3(0.0), cover(d - OUTLINE_PX * pw) * 0.55 * thin);
    vec3 lineColor = mix(base, vec3(1.0), 0.3 * strength);
    result = over(paint(lineColor, cover(d) * thin), result);

    // Шеврон посередине — направление передачи.
    float mid = len * 0.5;
    float head = arrowHeadDistance(p, mid - CHEVRON_LEN * 0.5, mid + CHEVRON_LEN * 0.5, CHEVRON_HALF);
    result = over(paint(vec3(0.0), cover(head - OUTLINE_PX * pw) * 0.55), result);
    result = over(paint(mix(base, vec3(1.0), 0.15), cover(head)), result);

    // Импульсы.
    if (strength > 0.0) {
        // На отдалении бусины сливаются в сплошную рябь — гасим их, пока расстояние между
        // ними не станет различимым.
        float spacingFade = smoothstep(5.0, 12.0, PULSE_SPACING / pw);

        float phase = p.x / PULSE_SPACING - u_time * PULSE_HZ - v_seed;
        float along = (fract(phase) - 0.5) * PULSE_SPACING;
        float radius = max(mix(0.45, 1.0, strength) * BEAD_RADIUS, BEAD_MIN_PX * pw);
        float bead = length(vec2(along, p.y));

        // У концов бусина въезжает в клетку и тает: видна только на самой связи.
        float ends = smoothstep(0.0, radius * 2.0, p.x) * smoothstep(0.0, radius * 2.0, len - p.x);
        float visible = ends * spacingFade;

        if (v_signal > 0.0) {
            float halo = (1.0 - smoothstep(radius, radius * BEAD_HALO, bead)) * 0.45 * strength;
            result = over(paint(mix(base, vec3(1.0), 0.5), halo * visible), result);
            result = over(paint(mix(base, vec3(1.0), 0.85), cover(bead - radius) * visible), result);
        } else {
            result = over(paint(mix(base, vec3(1.0), 0.8), cover(bead - radius) * visible), result);
            result = over(paint(vec3(0.03), cover(bead - radius * 0.6) * visible), result);
        }
    }
    return result;
}

/** Стрелка направления клетки: древко из центра и наконечник на конце. */
vec4 directionArrow(vec2 p) {
    float pw = u_pixelWorld;
    float len = v_length;

    float minHalf = SHAFT_MIN_HALF_PX * pw;
    float shaftHalf = max(SHAFT_HALF, minHalf);
    float thin = min(1.0, SHAFT_HALF / minHalf);

    float headLen = min(HEAD_LEN, len * 0.6);
    float shaft = segmentDistance(p, len - headLen * 0.5) - shaftHalf;
    float head = arrowHeadDistance(p, len - headLen, len, HEAD_HALF);
    float d = min(shaft, head);

    vec4 result = paint(vec3(0.0), cover(d - OUTLINE_PX * pw) * 0.6 * thin);
    vec3 color = mix(v_color.rgb, vec3(1.0), 0.35 * sqrt(signalMagnitude()));
    return over(paint(color, cover(d) * thin), result);
}

/**
 * Луч глаза: от обода до предела видимости. Светящийся след, тающий к концу, и короткая
 * поперечная черта — где зрение кончается. Когда глаз что-то видит (его выход не ноль),
 * луч ярче.
 */
vec4 eyeRay(vec2 p) {
    float pw = u_pixelWorld;
    float len = v_length;
    float strength = signalMagnitude();
    float bright = mix(0.4, 1.0, strength);
    float k = clamp(p.x / len, 0.0, 1.0);

    float d = segmentDistance(p, len);
    float glow = exp(-3.0 * (d * d) / (RAY_GLOW_HALF * RAY_GLOW_HALF)) * 0.3 * bright * (1.0 - k);
    vec4 result = paint(v_color.rgb, glow);

    float minHalf = RAY_MIN_HALF_PX * pw;
    float coreHalf = max(RAY_CORE_HALF, minHalf);
    float core = cover(d - coreHalf) * mix(0.2, 0.95, 1.0 - k) * bright;
    result = over(paint(mix(v_color.rgb, vec3(1.0), 0.45 * strength), core), result);

    float tick = length(vec2(p.x - len, max(abs(p.y) - RAY_TICK_HALF, 0.0))) - coreHalf;
    return over(paint(v_color.rgb, cover(tick) * 0.55), result);
}

/**
 * Жгутик хвоста: от поверхности клетки (x = 0) к кончику (x = длина). По нему от
 * основания к кончику бежит синусоида, размах растёт к кончику. Тяга хвоста (сигнал)
 * меняет размах, а не частоту — частота от сигнала сдвигала бы фазу рывком при каждой его
 * смене. Толщина сходит на нет к кончику; тёмная обводка — как у остальных линий.
 */
vec4 flagellum(vec2 p) {
    float pw = u_pixelWorld;
    float len = v_length;
    float x = clamp(p.x, 0.0, len);
    float s = x / len;

    float amplitude = FLAGELLUM_AMPLITUDE * len * mix(0.35, 1.0, signalMagnitude());
    float wavelength = FLAGELLUM_WAVELENGTH * len;
    float phase = 6.2831853 * (x / wavelength - fract(FLAGELLUM_HZ * u_time + v_seed));
    float y = amplitude * s * sin(phase);
    // Наклон кривой: расстояние по вертикали делится на него — так линия не толстеет на
    // крутых участках волны.
    float slope = amplitude * (sin(phase) / len + s * cos(phase) * 6.2831853 / wavelength);
    float d = length(vec2(p.x - x, (p.y - y) / sqrt(1.0 + slope * slope)));

    float minHalf = FLAGELLUM_MIN_HALF_PX * pw;
    float halfW = FLAGELLUM_HALF * len * (1.0 - 0.75 * s);
    float thin = min(1.0, halfW / minHalf);
    d -= max(halfW, minHalf);

    vec4 result = paint(vec3(0.0), cover(d - OUTLINE_PX * pw) * 0.5 * thin);
    return over(paint(mix(v_color.rgb, vec3(1.0), 0.3), cover(d) * thin), result);
}

/** Кольцо — радиус действия феромона: тонкая окружность и едва заметная заливка. */
vec4 ring(vec2 p) {
    float pw = u_pixelWorld;
    float r = length(p);
    vec4 result = paint(v_color.rgb, cover(r - v_length) * 0.05);
    return over(paint(v_color.rgb, cover(abs(r - v_length) - RING_HALF_PX * pw) * 0.6), result);
}

void main() {
    vec4 color;
    if (v_kind == KIND_NEURAL) {
        color = neuralLink(v_local);
    } else if (v_kind == KIND_ARROW) {
        color = directionArrow(v_local);
    } else if (v_kind == KIND_RAY) {
        color = eyeRay(v_local);
    } else if (v_kind == KIND_FLAGELLUM) {
        // Жгутик виден только над пустотой: над клетками — своей или чужой — его закрывает
        // тело, как и бахрому ресничек (cell_edge.frag).
        if (texelFetch(u_owner, ivec2(gl_FragCoord.xy), 0).a > 0.25) discard;
        color = flagellum(v_local);
    } else {
        color = ring(v_local);
    }

    if (color.a <= 0.0) discard;

    // Альфа цвета фигуры — общая непрозрачность.
    fragColor = color * v_color.a;
}
