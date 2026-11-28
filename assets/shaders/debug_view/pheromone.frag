#version 300 es
precision highp float;

// Пятно феромона: непрозрачность — сама концентрация A / (1 + K r^2) в этой точке, цвет —
// цвет феромона. Как в реалистичном режиме до 06.11 (shaders/pheromone/pheromone.frag).

in vec2 v_localUV;
in float v_A;
in float v_radius;
flat in vec3 ex_Color;

uniform float u_K;

out vec4 fragColor;

void main() {
    float distSq = dot(v_localUV, v_localUV);
    if (distSq > 1.0) discard;

    // distSq * v_radius — квадрат расстояния до центра в единицах мира.
    float alpha = v_A / (1.0 + u_K * distSq * v_radius);

    fragColor = vec4(ex_Color, alpha * 0.7);
}
