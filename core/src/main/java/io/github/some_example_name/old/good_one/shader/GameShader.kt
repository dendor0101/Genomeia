package io.github.some_example_name.old.good_one.shader

import io.github.some_example_name.old.good_one.shader.ShaderManager.Companion.CELLS_FLOAT_COUNT
import io.github.some_example_name.old.good_one.shader.ShaderManager.Companion.LINKS_FLOAT_COUNT
import io.github.some_example_name.old.good_one.shader.ShaderManager.Companion.SUBS_FLOAT_COUNT

val fragmentShader = """
#version 330 core

layout(std140) uniform CircleGrid {
    ivec2 u_grid[676];
    vec4 u_cells[1000];
};

uniform vec2 u_screenSize;
uniform float u_zoom;
uniform int u_playMode;
uniform int u_gridWidth;
uniform int u_gridHeight;
//uniform float u_cellSizeKef;

out vec4 fragColor;  // Замена gl_FragColor

float distanceToPoint(vec2 point, vec2 coord) {
    return distance(point, coord);
}

ivec2 getGridData(int index) {
    if (index < 0 || index >= 676) {
        return ivec2(-1, 0);
    }
    return u_grid[index];
}

float getFloat(int index) {
    int vecIndex = index / 4;
    int component = index % 4;

    return component == 0 ? u_cells[vecIndex].x :
           component == 1 ? u_cells[vecIndex].y :
           component == 2 ? u_cells[vecIndex].z :
                            u_cells[vecIndex].w;
}

struct CellData {
    vec2  position;
    vec2  velocity;
    vec3  color;
    float energy;
    int mode;
    int type;
    float angle;
};

void readGridCell(int gridIndex, vec2 fragCoord, inout CellData closest, inout CellData secondClosest, inout vec3 baseColor) {
    float maxRadius = 0.020833 * u_zoom;
    ivec2 cell = getGridData(gridIndex);
    if (cell.x == -1) return;

    int base = cell.x;
    int total = cell.y;

    int i = 0;
    while (i < total) {
        float type = getFloat(base + i);

        if (type == 1.0 && i + 11 < total) {
            CellData current;
            current.position = vec2(getFloat(base + i + 1), getFloat(base + i + 2)) / u_screenSize;
            current.position.y *= u_screenSize.y / u_screenSize.x;
            float dist = distance(current.position, fragCoord);

            if (dist < maxRadius) {
                current.velocity = vec2(getFloat(base + i + 3), getFloat(base + i + 4));
                current.color = vec3(getFloat(base + i + 5), getFloat(base + i + 6), getFloat(base + i + 7));
                current.energy = getFloat(base + i + 8);
                current.mode = int(getFloat(base + i + 9));
                current.type = int(getFloat(base + i + 10));
                current.angle = getFloat(base + i + 11);

                // Учитываем 9 вызовов - обновляем только если нашли ближе
                if (closest.type == -1 || dist < distance(closest.position, fragCoord)) {
                    secondClosest = closest;
                    closest = current;
                }
                else if (secondClosest.type == -1 || dist < distance(secondClosest.position, fragCoord)) {
                    secondClosest = current;
                }
            }
            i += $CELLS_FLOAT_COUNT;
        } else if (type == 2.0) {
            i += $LINKS_FLOAT_COUNT;
        } else if (type == 3.0) {
            //TODO getFloat4(base + i + 1);
            float x     = getFloat(base + i + 1);
            float y     = getFloat(base + i + 2);
//            float rad   = getFloat(base + i + 3);
            float r     = getFloat(base + i + 4);
            float g     = getFloat(base + i + 5);
            float b     = getFloat(base + i + 6);

            vec2 pos = vec2(x, y) / u_screenSize;
            pos.y *= u_screenSize.y / u_screenSize.x;
            vec2 delta = pos - fragCoord;
            float sqDist = dot(delta, delta); // Квадрат расстояния
            if (sqDist < 0.000027 * u_zoom * u_zoom) {
                if (baseColor == vec3(0.0)) {
                    baseColor = vec3(r, g, b);
                } else {
                    baseColor = mix(baseColor, vec3(r, g, b), 0.5);
                }
            }

            i += $SUBS_FLOAT_COUNT;
        }
        else {
            break;
        }
    }
}

void readGridCellLinks(int gridIndex, vec2 fragCoord, inout vec3 baseColorLink, inout bool isPainted) {
    ivec2 cell = getGridData(gridIndex);
    if (cell.x == -1) return;

    int base = cell.x;
    int total = cell.y;

    int i = 0;
    while (i < total) {
        float type = getFloat(base + i);

        if (type == 1.0 && i + 11 < total) {
            i += $CELLS_FLOAT_COUNT;
        } else if (type == 2.0) {
//            baseColorLink = vec3(1.0);
            float x1    = getFloat(base + i + 1);
            float y1    = getFloat(base + i + 2);
            float x2    = getFloat(base + i + 3);
            float y2    = getFloat(base + i + 4);
            float r1    = getFloat(base + i + 5);
            float g1    = getFloat(base + i + 6);
            float b1    = getFloat(base + i + 7);
            float r2    = getFloat(base + i + 8);
            float g2    = getFloat(base + i + 9);
            float b2    = getFloat(base + i + 10);

            vec2 c1 = vec2(x1, y1) / u_screenSize;
            c1.y *= u_screenSize.y / u_screenSize.x;
            vec2 c2 = vec2(x2, y2) / u_screenSize;
            c2.y *= u_screenSize.y / u_screenSize.x;

            // Вектор отрезка
            vec2 lineDir = c2 - c1;
            // Вектор от начальной точки до текущего пикселя
            vec2 pointDir = fragCoord - c1;

            // Проекция pointDir на lineDir
            float t = dot(pointDir, lineDir) / dot(lineDir, lineDir);
            t = clamp(t, 0.0, 1.0); // Ограничиваем t в пределах [0, 1]
            // Ближайшая точка на отрезке к текущему пикселю
            vec2 closestPoint = c1 + t * lineDir;
            // Расстояние от пикселя до ближайшей точки на отрезке
            float dist = distance(fragCoord, closestPoint);
            float l = distance(c2, c1) / 2;
            float r = 0.020833 * u_zoom;

            vec2 center = (c1 + c2) / 2.0;
            float x = distance(closestPoint, center);
            float R = l + r;
            float m = (r / (r + R)) * l;
            float H = sqrt((r+R)*(r+R) - l*l);
            float thickness = H - sqrt(R*R - x*x);
            if (x > l - m) thickness = 0.01 * u_zoom;
            if (thickness > 0.020833 * u_zoom) thickness = 0.020833 * u_zoom;

            if (dist < thickness) {
                vec3 color = mix(vec3(r1, g1, b1) * 0.5, vec3(r2, g2, b2) * 0.5, t);
                if (isPainted) {
                    baseColorLink = mix(baseColorLink, color, t);
                } else {
                    baseColorLink = color;
                }
                isPainted = true;
            }

            i += $LINKS_FLOAT_COUNT;
        } else if (type == 3.0) { i += $SUBS_FLOAT_COUNT; }
        else { break; }
    }
}


void main() {
    vec2 coord = gl_FragCoord.xy / u_screenSize; // Нормализация
    coord.y *= u_screenSize.y / u_screenSize.x;  // Компенсация за aspect ratio

    float cellSize = 0.020833 * 2; // Минимальное расстояние до ближайшей точки

    int cellX = int(coord.x / cellSize);
    int cellY = int(coord.y / cellSize);

    if (cellX < 0 || cellY < 0 || cellX >= u_gridWidth || cellY >= u_gridHeight) {
        fragColor = vec4(0.0);
        return;
    }

    CellData cellStruct = CellData(
        vec2(0.0, 0.0),      // position
        vec2(0.0, 0.0),      // velocity
        vec3(0.0, 0.0, 0.0), // color
        0.0,                 // energy
        -1,                  // mode
        -1,                  // type
        0.0                  // angle
    );
    CellData cellStructPrev = cellStruct;

    vec3 baseColor = vec3(0.0);

    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            readGridCell((cellY+y) * u_gridWidth + (cellX+x), coord, cellStruct, cellStructPrev, baseColor);
        }
    }

    if (cellStruct.type != -1) {
        float distToShell = distanceToPoint(cellStruct.position, coord);


        //Shell
        if (cellStructPrev.type == -1 && distToShell > 0.020833 * 0.8 * u_zoom) {
            if (cellStruct.mode == 3) {
                fragColor = vec4(0.0, 1.0, 0.0, 1.0);
            } else {
                fragColor = vec4(cellStruct.color * 0.7, 1.0);
            }
            return;
        }

        //Shell
        if (cellStructPrev.type != -1) {
            float distToShellSecond = distanceToPoint(cellStructPrev.position, coord);
            if (abs(distToShell - distToShellSecond) < 0.005 * u_zoom) {
                if (cellStruct.mode == 3) {
                    fragColor = vec4(0.0, 1.0, 0.0, 1.0);
                } else {
                    fragColor = vec4(cellStruct.color * 0.7, 1.0);
                }
                return;
            }
        }

        //Core
        if (distanceToPoint(cellStruct.position + cellStruct.velocity, coord) < cellStruct.energy * 0.001 * u_zoom) {
            fragColor = vec4(vec3(0.0), 1.0); // Черный цвет для точек
            return;
        }

        //Mass
        if (cellStruct.mode != 1 && cellStruct.mode != 3) {
            fragColor = vec4(cellStruct.color, 1.0);
            return;
        }
    }
    u_playMode;
    if (u_playMode == 1) {
        return;
    }

    vec3 baseColorLink = vec3(0.0);
    bool isPaintedLink = false;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            readGridCellLinks((cellY+y) * u_gridWidth + (cellX+x), coord, baseColorLink, isPaintedLink);
        }
    }

    if (baseColorLink != vec3(0.0)) {
        fragColor = vec4(baseColorLink, 1.0);
        return;
    }

    fragColor = vec4(baseColor, 1.0);
}
""".trimIndent()


// Вершинный шейдер
val vertexShader = """
attribute vec4 a_position;
void main() {
    gl_Position = a_position;
}
""".trimIndent()
