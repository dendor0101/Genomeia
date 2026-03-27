#version 320 es
precision highp float;

in vec2 v_texCoord;
in vec4 v_color;

out vec4 fragColor;

void main() {

    float dist = length(v_texCoord - vec2(0.5));
    float r = dist / 0.5;

    // ширина пикселя в экранном пространстве
    float aa = fwidth(dist);

    // сглаженный край круга
    float circle = 1.0 - smoothstep(0.5 - aa, 0.5 + aa, dist);

    float shade = 1.0;

    // центр
    float center = smoothstep(0.2 - aa, 0.2 + aa, r);
    shade *= mix(0.6, 1.0, center);

    // край
    float edge = smoothstep(0.8 - aa, 0.8 + aa, r);
    shade *= mix(1.0, 0.7, edge);

    fragColor = vec4(v_color.rgb * shade, v_color.a * circle);
}
