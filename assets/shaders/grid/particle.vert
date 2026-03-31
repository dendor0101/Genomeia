#version 320 es
precision highp float;

layout(location = 0) in vec2 aLocalPos;   // 0..1 для квадрата ячейки

const uint uGridWidth = 256;
const uint uGridHeight = 256;
const vec2 uCellSize = vec2(1.0, 1.0);      // размер одной ячейки в NDC, размер одной ячейки как 2 максимальных радиуса кружков

layout(std430, binding = 1) buffer GridCells {
    uint circleIndexes[];
};

flat out uvec2 vNeighbors[9];
flat out uint neighborsCellsCount;
out vec2 vWorldPos;

uniform mat4 u_projTrans;
uniform vec2 uGridOffset;    // смещение грида

void main() {
    uint instID = uint(gl_InstanceID);
    uint gridX = instID % uGridWidth;
    uint gridY = instID / uGridWidth;

    // === 9 соседей (включая себя) ===
    const ivec2 offsets[9] = ivec2[](
        ivec2( 0,  0), ivec2( 1,  0), ivec2(-1,  0),
        ivec2( 0,  1), ivec2( 1,  1), ivec2(-1,  1),
        ivec2( 0, -1), ivec2( 1, -1), ivec2(-1, -1)
    );

    bool hasAnyCircle = false;
    uint counter = 0;

    for (int i = 0; i < 9; ++i) {
        int nx = int(gridX) + offsets[i].x;
        int ny = int(gridY) + offsets[i].y;

        if (nx >= 0 && nx < int(uGridWidth) && ny >= 0 && ny < int(uGridHeight)) {
            uint nID = uint(ny) * uGridWidth + uint(nx);

            uint circleIndex = circleIndexes[nID];
            uint circleAmount = circleIndexes[nID + 1] - circleIndexes[nID];

            if (circleAmount > 0u) {
                hasAnyCircle = true;
                vNeighbors[counter] = uvec2(circleIndex, circleAmount);
                counter++;
            }
        }
    }
    neighborsCellsCount = counter;

    // === CULLING ===
    // 1. Все 9 ячеек пустые
    if (!hasAnyCircle) {
        gl_Position = vec4(3.0, 3.0, 0.0, 1.0); // полностью за клипом
        vWorldPos = vec2(0.0);
        return;
    }

    // 2. Ячейка полностью за пределами экрана
    vec2 cellMin = uGridOffset + vec2(float(gridX), float(gridY)) * uCellSize;
    vec2 cellMax = cellMin + uCellSize;
    bool outOfScreen = (cellMax.x < -1.0 || cellMin.x > 1.0 ||
    cellMax.y < -1.0 || cellMin.y > 1.0);

    if (outOfScreen) {
        gl_Position = vec4(3.0, 3.0, 0.0, 1.0);
        vWorldPos = vec2(0.0);
        return;
    }

    // === нормальная позиция ===
    vec2 worldPos = cellMin + aLocalPos * uCellSize;
    vWorldPos = worldPos;
    gl_Position = u_projTrans * vec4(worldPos, 0.0, 1.0);   // если уже в NDC
}
