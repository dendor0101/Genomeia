#version 320 es
precision highp float;

layout(location = 0) in vec2 a_position;

struct Circle {
    vec2 pos;
    uint color;
    uint packed1;
    uint packed2;
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
flat out float ex_Angle;           // ← добавлено: угол поворота текстуры
flat out float ex_Energy;
flat out int ex_cellType;

void main() {
    int id = gl_InstanceID;

    Circle curr = (u_currentBuffer == 0u) ? circlesA[id] : circlesB[id];
    Circle prev = (u_currentBuffer == 0u) ? circlesB[id] : circlesA[id];

    vec2 velocity = curr.pos - prev.pos;

    vec4 v1 = unpackUnorm4x8(curr.packed1);
    vec4 v2 = unpackUnorm4x8(curr.packed2);

    float lowByte  = v1.x * 255.0;
    float highByte = v1.y * 255.0;
    float angle16  = lowByte + highByte * 256.0;

    float angleNorm = angle16 / 65535.0;

    // Восстанавливаем оригинальный диапазон -PI … +PI
    float angle = angleNorm * -2.0 * 3.14159265 - 3.14159265;

    // ax и ay теперь:
    // ax = v1.y  ← будет содержать highByte/255 (если ax тебе потом понадобится — скажи, переложим)
    // ay = v1.z  ← остаётся 0
    float ax = v1.y;
    float ay = v1.z;

    ex_R     = 0.1 + v1.w * 0.4;                 // radius на месте
    float energy   = v2.x * 10.0 * 0.0;
    int   cellType = int(round(v2.y * 255.0));

    vec2 worldPos = a_position * ex_R + curr.pos;

    ex_Quad = worldPos;
    ex_Centroid = curr.pos;
    ex_Velocity = velocity;
    ex_Color = unpackUnorm4x8(curr.color).rgb;
    ex_R_2 = ex_R * ex_R;
    ex_Energy = energy * energy * 0.0005;
    ex_UV = a_position * 0.5 + 0.5;   // -1..1 → 0..1
    ex_cellType = cellType;

    ex_Angle = angle + sin(float(id)) * 3.0;        // ← добавлено: передаём угол в радианах

    gl_Position = u_projTrans * vec4(worldPos, 0.0, 1.0);
}
