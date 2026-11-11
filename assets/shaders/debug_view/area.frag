#version 300 es
precision highp float;

in vec3 v_color;
layout(location = 0) out vec4 fragColor;
layout(location = 1) out vec4 fragOwner;

void main() {
    fragColor = vec4(v_color, 1.0);
    fragOwner = vec4(0.0);
}
