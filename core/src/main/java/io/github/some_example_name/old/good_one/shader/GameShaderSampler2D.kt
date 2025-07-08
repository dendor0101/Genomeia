package io.github.some_example_name.old.good_one.shader

import io.github.some_example_name.old.good_one.shader.ShaderManager.Companion.CELLS_FLOAT_COUNT
import io.github.some_example_name.old.good_one.shader.ShaderManager.Companion.LINKS_FLOAT_COUNT
import io.github.some_example_name.old.good_one.shader.ShaderManager.Companion.SUBS_FLOAT_COUNT

val fragmentShaderSampler2d = """
#version 300 es
precision highp float;//TODO highp float для мобилы
precision highp int;
precision highp isampler2D;//TODO isampler2D не работает в мобиле

uniform isampler2D u_intTexture;
uniform sampler2D u_floatTexture;
uniform vec2 u_screenSize;
uniform float u_zoom;
uniform int u_playMode;
uniform float u_aspectRatio;
//uniform float u_cellSize;
uniform float u_cellSizepx;
uniform vec2 u_gridOffset;
uniform float u_squareZoomMaxRadius; //maxRadiusSq * u_zoom * u_zoom
uniform float u_squareZoomMaxRadius08; //0.000277 * u_zoom * u_zoom // (0.020833*0.8)^2
uniform float u_link_r; // 0.020833 * u_zoom

//const float cellSize = 0.041666 / 2.0; //Размер шарика
const float maxRadiusSq = 0.000434; // (0.020833^2) pre-calculated
const float subsRadiusSq = 0.000027; // pre-calculated

out vec4 fragColor;

struct CellData {
    vec2 position;
    vec2 velocity;
    float energy;
    vec3 color;
    int mode;
    int type;
};

float distanceToPoint(vec2 point, vec2 coord) {
    return distance(point, coord);
}

float getFloat(int index) {
    ivec2 texCoord = ivec2((index >> 2) & 255, index >> 10); // index/4 %256, index/1024
    return texelFetch(u_floatTexture, texCoord, 0)[index & 3];
}

void readGridCell(ivec2 cellivec, vec2 fragCoord, inout CellData closest, inout CellData secondClosest, inout vec3 baseColor) {
    int base = cellivec.x;
    if (base == 1) return;
    int total = cellivec.y;
    int i = 0;

    while (i < total) {
        float type = getFloat(base + i);

        if (type == 1.0 && i + ${CELLS_FLOAT_COUNT - 1} < total) {
            vec2 position = vec2(getFloat(base + i + 1), getFloat(base + i + 2));
            vec2 delta = position - fragCoord;
            float distSq = dot(delta, delta);

            if (distSq < u_squareZoomMaxRadius) { //Размер шарика
                CellData current;
                current.position = position;
                current.velocity = vec2(getFloat(base + i + 3), getFloat(base + i + 4));
                current.energy = getFloat(base + i + 5);
                current.color = vec3(getFloat(base + i + 6), getFloat(base + i + 7), getFloat(base + i + 8));
                current.mode = int(getFloat(base + i + 9));

                if (closest.type == -1 || distSq < dot(closest.position - fragCoord, closest.position - fragCoord)) {
                    secondClosest = closest;
                    closest = current;
                }
                else if (secondClosest.type == -1 || distSq < dot(secondClosest.position - fragCoord, secondClosest.position - fragCoord)) {
                    secondClosest = current;
                }
            }
            i += $CELLS_FLOAT_COUNT;
        }
        else if (type == 2.0) {
            i += $LINKS_FLOAT_COUNT;
        }
        else if (type == 3.0) {
            vec2 pos = vec2(getFloat(base + i + 1), getFloat(base + i + 2)) / u_screenSize;
            pos.y *= u_aspectRatio;
            vec2 delta = pos - fragCoord;
            if (dot(delta, delta) < subsRadiusSq * u_zoom * u_zoom) {
                vec3 subColor = vec3(getFloat(base + i + 4), getFloat(base + i + 5), getFloat(base + i + 6));
                baseColor = mix(baseColor, subColor, float(baseColor == vec3(0.0)) * 0.5);
            }
            i += $SUBS_FLOAT_COUNT;
        }
        else break;
    }
}


void readGridCellLinks(ivec2 cellivec, vec2 fragCoord, inout vec3 baseColorLink, inout bool isPainted) {
    int base = cellivec.x;
    if (base == 1) return;
    int total = cellivec.y;

    int i = 0;
    while (i < total) {
        float type = getFloat(base + i);

        if (type == 1.0 && i + 11 < total) {
            i += $CELLS_FLOAT_COUNT;
        } else if (type == 2.0) {
            vec2 c1     = vec2(getFloat(base + i + 1), getFloat(base + i + 2));
            vec2 c2     = vec2(getFloat(base + i + 3), getFloat(base + i + 4));
            vec3 color1    = vec3(getFloat(base + i + 5), getFloat(base + i + 6), getFloat(base + i + 7));
            vec3 color2    = vec3(getFloat(base + i + 8), getFloat(base + i + 9), getFloat(base + i + 10));

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
            float l = distance(c2, c1) / 2.0;
            float r = u_link_r;

            vec2 center = (c1 + c2) / 2.0;
            float x = distance(closestPoint, center);
            float R = l + r;
            float m = (r / (r + R)) * l;
            float H = sqrt((r+R)*(r+R) - l*l);
            float thickness = H - sqrt(R*R - x*x);
            if (x > l - m) thickness = 0.01 * u_zoom;
            if (thickness > r) thickness = r;

            if (dist < thickness) {
                vec3 color = mix(color1, color2, t) * 0.5;
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

    ivec2 gridCoord = ivec2((gl_FragCoord.xy + u_gridOffset) / u_cellSizepx);
    int base = texelFetch(u_intTexture, gridCoord, 0).x;
    if (base == 2) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    vec2 coord = gl_FragCoord.xy / u_screenSize;
    coord.y *= u_aspectRatio;


    vec3 baseColor = vec3(0.0);


    CellData closest = CellData(
        vec2(0),
        vec2(0),
        0.0,
        vec3(0),
        -1,
        -1
    );
    CellData secondClosest = closest;

    // Unrolled loop for better performance
    for (int y = -1; y <= 1; y++) {
        ivec2 cell0 = texelFetch(u_intTexture, gridCoord + ivec2(-1, y), 0).xy;
        ivec2 cell1 = texelFetch(u_intTexture, gridCoord + ivec2(0, y), 0).xy;
        ivec2 cell2 = texelFetch(u_intTexture, gridCoord + ivec2(1, y), 0).xy;

        readGridCell(cell0, coord, closest, secondClosest, baseColor);
        readGridCell(cell1, coord, closest, secondClosest, baseColor);
        readGridCell(cell2, coord, closest, secondClosest, baseColor);
    }

    if (closest.type != -1) {
        float distSq = dot(closest.position - coord, closest.position - coord);

        // Shell rendering
        if (secondClosest.type == -1) {
            if (distSq > u_squareZoomMaxRadius08) { // (0.020833*0.8)^2 //Размер шарика
                fragColor = vec4(closest.mode == 3 ? vec3(0,1,0) : closest.color * 0.7, 1);
                return;
            }
        }
        else {
            float distSq2 = dot(secondClosest.position - coord, secondClosest.position - coord);
            if (abs(sqrt(distSq) - sqrt(distSq2)) < 0.004166 * u_zoom) { //Размер шарика
                fragColor = vec4(closest.mode == 3 ? vec3(0,1,0) : closest.color * 0.7, 1);
                return;
            }
        }

        //TODO вернуть ядро
        //Core
        if (distanceToPoint(closest.position + closest.velocity, coord) < closest.energy * 0.001 * u_zoom) {
            fragColor = vec4(vec3(0.0), 1.0); // Черный цвет для точек
            return;
        }

        // Core and mass rendering
        if (closest.mode != 1 && closest.mode != 3) {
            fragColor = vec4(closest.color, 1);
            return;
        }
    }


//    vec2 pixelCoord = floor(gl_FragCoord.xy) + u_gridOffset;
//    // Линии сетки толщиной 1 пиксель
//    bool isGridLineX = int(mod(pixelCoord.x, u_cellSizepx)) == 0;
//    bool isGridLineY = int(mod(pixelCoord.y, u_cellSizepx)) == 0;
//    // Цвет: белый для линий сетки, черный для фона
//    baseColor = (isGridLineX || isGridLineY) ? vec3(0.3) : baseColor;

    vec3 baseColorLink = vec3(0.0);
    bool isPaintedLink = false;
    for (int y = -1; y <= 1; y++) {
        ivec2 cell0 = texelFetch(u_intTexture, gridCoord + ivec2(-1, y), 0).xy;
        ivec2 cell1 = texelFetch(u_intTexture, gridCoord + ivec2(0, y), 0).xy;
        ivec2 cell2 = texelFetch(u_intTexture, gridCoord + ivec2(1, y), 0).xy;

        readGridCellLinks(cell0, coord, baseColorLink, isPaintedLink);
        readGridCellLinks(cell1, coord, baseColorLink, isPaintedLink);
        readGridCellLinks(cell2, coord, baseColorLink, isPaintedLink);
    }

    if (baseColorLink != vec3(0.0)) {
        fragColor = vec4(baseColorLink, 1.0);
        return;
    }

    if (u_playMode != 1) {
        fragColor = vec4(baseColor, 1);
    }
}
""".trimIndent()


// Вершинный шейдер
val vertexShaderSampler2d = """
#version 300 es
precision mediump float; // Точность (обязательно для GLES 3.0)

in vec4 a_position; // Заменили 'attribute' на 'in'

void main() {
    gl_Position = a_position;
}
""".trimIndent()
