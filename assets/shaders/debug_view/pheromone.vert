#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;

// Феромоны отладочного режима — как в реалистичном режиме до 06.11 (shaders/pheromone/
// pheromone_pc.vert, удалён в ae52774 вместе с реалистичным режимом). Квад на феромон
// размером ровно в радиус действия: там, где концентрация A / (1 + K r^2) падает до порога P.

layout (location = 0) in vec2 a_position;

uniform mat4 u_projTrans;
uniform float u_K;
uniform float u_P;

// RGBA32UI: один тексель на феромон — putFloat(x), putFloat(y), putFloat(A), putInt(color)
// (PheromoneInstanceBuffer). Целочисленная текстура: во float-текстуре драйвер вправе
// канонизировать NaN и испортить биты цвета.
uniform usampler2D u_data;
uniform int u_texWidth;

out vec2 v_localUV;
out float v_A;
out float v_radius;
flat out vec3 ex_Color;

void main() {
    int id = gl_InstanceID;
    int texX = id - (id / u_texWidth) * u_texWidth;
    int texY = id / u_texWidth;

    uvec4 ph = texelFetch(u_data, ivec2(texX, texY), 0);

    vec2 worldPos = vec2(uintBitsToFloat(ph.x), uintBitsToFloat(ph.y));
    v_A = uintBitsToFloat(ph.z);

    // RGBA8 вручную: unpackUnorm4x8 есть только с GLSL ES 3.10.
    uint c = ph.w;
    ex_Color = vec3(
        float(c & 0xFFu),
        float((c >> 8u) & 0xFFu),
        float((c >> 16u) & 0xFFu)
    ) * (1.0 / 255.0);

    // Радиус действия в квадрате — как PheromonesManager.radiusSquared.
    float squaredRadius = max((v_A / u_P - 1.0) / u_K, 0.0);
    float radius = sqrt(squaredRadius);

    gl_Position = u_projTrans * vec4(worldPos + a_position * radius, 0.0, 1.0);

    v_localUV = a_position;
    v_radius = squaredRadius;
}
