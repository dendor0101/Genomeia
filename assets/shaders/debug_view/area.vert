#version 300 es
precision highp float;

#include "debug_color.glsl"

layout(location = 1) in vec2 a_center;
layout(location = 2) in vec4 a_color;
layout(location = 5) in vec4 a_look;
layout(location = 7) in vec2 a_prevCenter;

uniform mat4 u_projTrans;
uniform float u_lag;
uniform int u_filter;
out vec3 v_color;

void main() {
    float filtered;
    v_color = debugColor(a_color.rgb, floor(a_look.w * 255.0 + 0.5), true, u_filter, filtered);
    gl_Position = u_projTrans * vec4(mix(a_center, a_prevCenter, u_lag), 0.0, 1.0);
}
