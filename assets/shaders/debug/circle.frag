#version 300 es
precision highp float;
precision highp sampler2DArray;

/**
 * d/R в этом фрагменте: 0 в центре клетки, 1 на ободе.
 *
 * Приходит интерполяцией по вееру — см. пояснение в circle_pc.vert. Заменяет собой всё,
 * что раньше считалось здесь на каждом фрагменте: ex_Quad, ex_Centroid, разность,
 * dot(), деление на R² и sqrt для расстояния.
 */
in float ex_R_norm;

in vec2 ex_UV;
flat in vec3 ex_Color;
flat in float ex_R;
flat in float ex_Energy;
flat in float ex_AngleCos;
flat in float ex_AngleSin;
flat in int ex_cellType;
flat in vec2 ex_Refract;   // ← случайный вектор рефракции (от инстанса)

out vec4 fragColor;

uniform sampler2DArray u_textureArray;
uniform float u_colorScale;

vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

void main() {
    // Ни discard, ни записи gl_FragDepth здесь больше нет: силуэт задаёт геометрия веера,
    // а глубину пишет растеризатор. Благодаря этому клетки — полностью непрозрачная
    // геометрия, и early-Z отбрасывает перекрытые фрагменты ДО этого шейдера.
    float normalized = ex_R_norm * ex_R_norm;   // (d/R)², как было dist2 / ex_R_2

    // === UV ПОВОРОТ ===
    vec2 center = vec2(0.5);
    vec2 offset = ex_UV - center;

    float ca = ex_AngleCos;
    float sa = ex_AngleSin;

    vec2 rotatedOffset = vec2(ca * offset.x - sa * offset.y, sa * offset.x + ca * offset.y);
    vec2 rotatedUV = center + rotatedOffset;

    // === РЕФРАКЦИЯ (искажение) — полностью от инстанса, угол не влияет ===
    vec2 refraction = ex_Refract * 0.13 * (1.0 - normalized);
    vec2 distortedUV = rotatedUV + refraction;

    vec4 texColor = texture(u_textureArray, vec3(distortedUV, float(ex_cellType)));

    // === СМЕШИВАНИЕ С ЦВЕТОМ КЛЕТКИ ===
    vec3 texHSV = rgb2hsv(texColor.rgb);
    vec3 targetHSV = rgb2hsv(ex_Color);
    texHSV.x = targetHSV.x;
    vec3 tinted = hsv2rgb(texHSV);

    vec3 finalColor = tinted;

    // === ЧЕРНЫЙ КРУЖОЧЕК ===
    float dist = ex_R_norm * ex_R;   // было length(diff)

    float edgeWidth = ex_R * 0.018;
    float energyRadius = max(ex_Energy, 0.0);

    float energyMask = 1.0 - smoothstep(energyRadius, energyRadius + edgeWidth, dist);

    // Полностью убираем даже точку при ex_Energy = 0
    energyMask *= step(0.0001, ex_Energy);

    float blackStrength = energyMask * u_colorScale;

    finalColor = mix(finalColor, vec3(0.0), blackStrength);

    fragColor = vec4(finalColor, 1.0);
}
