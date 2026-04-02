#version 320 es
precision highp float;

layout(location = 0) in vec2 a_position;

struct Circle {
    vec2 pos;
    float size;
    uint color;
};

layout(std430, binding = 0) buffer CirclesA { Circle circlesA[]; };
layout(std430, binding = 1) buffer CirclesB { Circle circlesB[]; };

uniform mat4 u_projTrans;
uniform uint u_currentBuffer;

out vec2 ex_Quad;
flat out vec2 ex_Centroid;
flat out vec2 ex_Velocity;
flat out vec3 ex_Color;
flat out float ex_R;
flat out float ex_R_2;
out vec2 ex_UV;                    // локальные UV для текстуры

void main() {
    int id = gl_InstanceID;

    Circle curr = (u_currentBuffer == 0u) ? circlesA[id] : circlesB[id];
    Circle prev = (u_currentBuffer == 0u) ? circlesB[id] : circlesA[id];

    vec2 velocity = curr.pos - prev.pos;

    vec2 worldPos = a_position * curr.size + curr.pos;

    ex_Quad = worldPos;
    ex_Centroid = curr.pos;
    ex_Velocity = velocity;
    ex_Color = unpackUnorm4x8(curr.color).rgb;

    ex_R = curr.size;
    ex_R_2 = curr.size * curr.size;

    ex_UV = a_position * 0.5 + 0.5;   // -1..1 → 0..1

    gl_Position = u_projTrans * vec4(worldPos, 0.0, 1.0);
}
