#version 300 es
precision highp float;

// Проход 3 отладочного режима: сглаживание краёв клеток, стенки между ними и бахрома
// рисунков — прямо в экран, поверх готовой заливки.
//
// КАК ЭТО РАБОТАЕТ
// ----------------
// Заливка (проход 1) оставила в глубине минимальное d/R по пикселю — то есть d/R
// владельца, а во втором буфере — его градиент d/R и группу слияния. Здесь каждая клетка
// снова проходит по своему корпусу и сравнивает своё d/R с этим минимумом:
//   - равны — пиксель её собственный, заливка его уже нарисовала: discard;
//   - больше — пиксель чужой, и разница d/R, делённая на разность градиентов, — это
//     расстояние в пикселях до границы её владений.
// По этой дистанции проигравшая клетка подмешивает свой цвет в полупиксельную полосу у
// границы (это и есть сглаживание: с двух сторон по половине пикселя) и рисует свою
// половину стенки. Вторую половину рисует сосед — с его стороны проигравшая уже она.
// Соседи одной группы слияния (кость к кости, мышца к мышце) стенку не рисуют.
//
// Внешний край тела устроен так же, только «соседом» выступает пустота с d/R = 1: глубина
// очищена именно этим значением. Получается аналитический аналог MSAA — без лишних
// выборок, по одному чтению глубины на фрагмент, и большинство фрагментов (далеко от
// границ) отбрасываются сразу после него.
//
// Над пустотой за окружностью клетка рисует бахрому своего рисунка — реснички, иглы,
// шипы. Над чужой клеткой бахромы нет: внутри тела её закрывают соседи.
//
// Полупрозрачная клетка («призрак») рисуется здесь целиком и поверх всего — в заливке
// её нет, см. cell_fill.frag.

#include "cell_look.glsl"
#include "cell_common.glsl"

/** Глубина прохода заливки: d/R владельца пикселя, умноженное на u_depthScale. */
uniform sampler2D u_depth;
uniform float u_depthScale;

/** Градиент d/R владельца пикселя и его группа — см. encodeOwner в cell_common.glsl. */
uniform sampler2D u_owner;

out vec4 fragColor;

/**
 * Владелец пикселя — это я сам, если его градиент d/R совпадает с моим (с точностью до
 * упаковки в байты, это несколько процентов).
 *
 * Сравнивать одни только d/R нельзя: два прохода — две разные программы, и интерполятор
 * вправе разойтись в младших битах. На Intel UHD d/R одной и той же клетки в одном
 * пикселе расходился на ~1.5e-5, и клетка рисовала «стенку» сама себе — тёмный клин
 * внутри. А любой порог по d/R задевал и настоящих соседей у самой границы: стенка
 * шла пунктиром. Градиент же у соседа другой всегда — иначе у них не было бы границы.
 */
const float SELF_SLOPE = 0.15;

/** «Призрак»: вся клетка с рисунком и мембраной, с непрозрачностью v_opacity. */
vec4 ghost(float t, float tpp) {
    float rimPx = (1.0 - t) / tpp;
    float cover = coverage(rimPx);
    if (cover <= 0.0) return vec4(0.0);
    vec3 color = cellColor(min(t, 1.0));
    if (hasMembrane(v_pattern)) {
        float membrane = clamp(MEMBRANE_PX + 0.5 - rimPx, 0.0, 1.0) * detailFade(tpp);
        color = mix(color, membraneColor(v_color), membrane);
    }
    return paint(color, cover * v_opacity);
}

void main() {
    float t = length(v_local);
    float tpp = v_tPerPixel;

    if (v_opacity < 1.0) {
        vec4 result = ghost(t, tpp);
        if (result.a <= 0.0) discard;
        fragColor = result;
        return;
    }

    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float tMin = texelFetch(u_depth, pixel, 0).r / u_depthScale;
    float gap = t - tMin;

    // Точный владелец — сразу, без второй выборки: таких фрагментов большинство.
    if (gap <= 0.0) discard;

    // До собственного обода — точно. До стенки с владельцем — по разности градиентов
    // d/R двух клеток: она и есть скорость, с которой растёт gap на пиксель.
    float rimPx = (t - 1.0) / tpp;
    vec4 owner = texelFetch(u_owner, pixel, 0);
    bool occupied = owner.a > 0.25;
    float wallPx = 1e6;
    if (occupied) {
        vec2 slope = tGradient(v_local, t, tpp) - decodeOwnerGradient(owner);
        float speed = length(slope);
        if (speed < SELF_SLOPE * tpp) discard;
        wallPx = gap / speed;
    }

    float fade = detailFade(tpp);

    // Бахрома — только над пустотой и только у рисунков, где она есть.
    vec4 fringe = vec4(0.0);
    float fringeFade = patternFade(tpp);
    if (!occupied && t > 1.0 && fringeFade > 0.0 && fringeLength(v_pattern) > 0.0) {
        fringe = fringeInk(cellFrame(), t) * fringeFade;
    }

    // Над пустотой владельца нет (owner занят нулём), и ближайшая граница — свой обод.
    bool atRim = rimPx >= wallPx;
    float distPx = atRim ? rimPx : wallPx;

    if (distPx > WALL_HALF_PX + 1.0) {
        if (fringe.a <= 0.0) discard;
        fragColor = fringe;
        return;
    }

    float cover = clamp(0.5 - distPx, 0.0, 0.5);
    // В полупиксельной полосе у соседа — только ткань, без рисунка: разницы не видно, а
    // рисунок здесь считался бы для каждого пикселя каждой границы второй раз.
    vec3 color = atRim ? rimColor(fade) : bodyColor(t);
    vec4 result = vec4(color * cover, cover);

    if (!atRim) {
        int group = mergeGroup(v_pattern);
        bool merged = group != 0 && ownerGroup(owner) == group;
        float wall = merged ? 0.0 : clamp(WALL_HALF_PX + 0.5 - wallPx, 0.0, 1.0) * WALL_ALPHA * fade;
        // Стенка поверх подмешанного цвета; чёрная, поэтому в премноженном виде — ноль.
        result = result * (1.0 - wall) + vec4(0.0, 0.0, 0.0, wall);
    }

    fragColor = over(result, fringe);
}
