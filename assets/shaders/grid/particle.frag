#version 320 es
precision highp float;

flat in uvec2 vNeighbors[9];
flat in uint neighborsCellsCount;
in vec2 vWorldPos;

struct Circle {
    vec2 pos;
    float size; // радиус влияния (используется только на CPU при биннинге)
    uint color; // packed ARGB
};

layout(std430, binding = 0) buffer Circles {
    Circle circles[];
};

out vec4 fragColor;

void main() {
    vec2 pos = vWorldPos;

    float minDistSq = 1e20;
    uint bestColor = 0u; // фон по умолчанию (можно сделать uniform)

    // 9 фиксированных ячеек — очень быстро на мобильных GPU
    //TODO сделать обход не 9 соседей а 4, выбор четырех соседей в зависимости позиции пикселя в ячейке
    for (uint n = 0u; n < neighborsCellsCount; ++n) {
        uint start = vNeighbors[n].x;
        uint count = vNeighbors[n].y;

        for (uint i = 0u; i < count; ++i) {
            Circle c = circles[start + i];

            vec2 delta = pos - c.pos;
            float distSq = dot(delta, delta);

            if (distSq < minDistSq) {
                minDistSq = distSq;
                bestColor = c.color;
            }
        }
    }

    vec4 col = unpackUnorm4x8(bestColor).zyxw;   // ARGB → RGBA
    fragColor = col;
}
