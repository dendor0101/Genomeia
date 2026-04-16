#version 320 es
precision highp float;
precision highp sampler2DArray;

in vec2 ex_Quad;
flat in vec2 ex_Centroid;
flat in vec2 ex_Velocity;
flat in vec3 ex_Color;
flat in float ex_R;
flat in float ex_R_2;
flat in float ex_Energy;
in vec2 ex_UV;
flat in float ex_AngleCos;
flat in float ex_AngleSin;
flat in int ex_cellType;

out vec4 fragColor;

uniform sampler2DArray u_textureArray;
uniform float u_textureScale;
uniform float u_colorScale;

void main() {
    vec2 diff = ex_Quad - ex_Centroid;
    float dist2 = dot(diff, diff);
    if (dist2 > ex_R_2) discard;

    float normalized = dist2 / ex_R_2;

    // === НОРМАЛЬ (псевдо 3D) ===
    float z = ex_R * (1.0 - normalized * 0.5);
    vec3 normal = normalize(vec3(diff, z));

    // === UV ПОВОРОТ ===
    vec2 center = vec2(0.5);
    vec2 offset = ex_UV - center;

    float ca = ex_AngleCos;
    float sa = ex_AngleSin;

    vec2 rotatedOffset = vec2(ca * offset.x - sa * offset.y, sa * offset.x + ca * offset.y);
    vec2 rotatedUV = center + rotatedOffset;

    // === РЕФРАКЦИЯ (искажение) ===
    vec2 refraction = normal.xy * 0.13 * (1.0 - normalized);
    vec2 distortedUV = rotatedUV * u_textureScale + refraction;

    vec4 texColor = texture(u_textureArray, vec3(distortedUV, float(ex_cellType)));

    // === СМЕШИВАНИЕ С ЦВЕТОМ КЛЕТКИ ===
    vec3 finalColor = mix(texColor.rgb, ex_Color, u_colorScale);

    fragColor = vec4(finalColor, 1.0);

    // === DEPTH ===
    gl_FragDepth = 1.0 - (z / ex_R);
}
