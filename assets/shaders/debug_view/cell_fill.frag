#version 300 es
precision highp float;

// Проход 1 отладочного режима: заливка клеток в собственный FBO DebugRenderer.
//
// Глубина — точное d/R (как в ностальгическом режиме), поэтому тест GL_LESS оставляет
// в каждом пикселе клетку с наименьшим d/R: мультипликативно взвешенная диаграмма
// Вороного. Её же глубину читает второй проход (cell_edge.frag), чтобы сгладить границы.
//
// Цвет пишется с премножением на покрытие: у внешнего края тела фрагмент знает, сколько
// пикселя занимает его круг, и сглаживает край внутрь сам. Наружу край сглаживает второй
// проход, там же рисуются стенки между клетками и бахрома рисунков (реснички, иглы).
//
// Полупрозрачных клеток («призрак» в редакторе генома) здесь нет: в диаграмме Вороного
// они отрезали бы куски у соседей. Их целиком рисует проход 3, поверх готовой картинки.

#include "cell_look.glsl"
#include "cell_common.glsl"

/** Глубина окна = d/R * u_depthScale; очистка — u_depthScale, то есть d/R = 1. */
uniform float u_depthScale;

layout(location = 0) out vec4 fragColor;

/**
 * Владелец пикселя для прохода сглаживания: куда и как быстро растёт его d/R и его
 * группа слияния — см. encodeOwner. Без этого соседу пришлось бы угадывать, а угадывание
 * ломается на клетках разного радиуса: граница между ними — дуга, и стенка расплывалась
 * в широкую тень.
 */
layout(location = 1) out vec4 fragOwner;

void main() {
    float t = length(v_local);

    // Корпус раздут под сглаживание; заливке нужен только сам круг.
    if (t >= 1.0 || v_opacity < 1.0) discard;

    gl_FragDepth = t * u_depthScale;

    float tpp = v_tPerPixel;
    float fade = detailFade(tpp);

    vec3 color = cellColor(t);

    // Мембрана — полоса у обода. Видна только там, где клетка граничит с пустотой:
    // внутри тела обод перекрыт соседом, и владелец пикселя там уже не эта клетка.
    float rimPx = (1.0 - t) / tpp;
    if (hasMembrane(v_pattern)) {
        float membrane = clamp(MEMBRANE_PX + 0.5 - rimPx, 0.0, 1.0) * fade;
        color = mix(color, membraneColor(v_color), membrane);
    }

    float cover = coverage(rimPx);
    fragColor = vec4(color * cover, cover);
    fragOwner = encodeOwner(v_local, t, tpp, mergeGroup(v_pattern));
}
