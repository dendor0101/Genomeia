#version 320 es
precision highp float;
layout(location = 0) in vec2 a_position;

struct Circle {
    vec2 pos;
    float size;
    uint color; // packed RGBA
};

layout(std430, binding = 0) buffer Circles {
    Circle circles[];
};

out vec2 v_texCoord;
out vec4 v_color;
uniform mat4 u_projTrans;
void main() {
    int instID = gl_InstanceID;
    Circle c = circles[instID];
    vec2 offsetPos = a_position * c.size + c.pos;
    v_texCoord = a_position * 0.5 + 0.5;
    v_color = unpackUnorm4x8(c.color);
    gl_Position = u_projTrans * vec4(offsetPos, 0.0, 1.0);
}
