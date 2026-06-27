#version 150

uniform float u_h;

in vec4 v_color;
in vec2 v_texCoords;

out vec4 fragColor;

vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

void main() {
    fragColor = v_color * vec4(hsv2rgb(vec3(u_h, 1.0 - v_texCoords.t, v_texCoords.s)), 1.0);
}
