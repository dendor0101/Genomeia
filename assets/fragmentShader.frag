#version 300 es
precision highp float;//TODO highp float для мобилы
precision highp int;
precision highp isampler2D;//TODO isampler2D не работает в мобиле

uniform isampler2D u_intTexture;
uniform sampler2D u_floatTexture;
uniform sampler2D u_pheromoneFloatTexture;
uniform vec2 u_screenSize;
//uniform float u_zoom;
//uniform int u_playMode;
uniform float u_aspectRatio;
//uniform float u_cellSize;
uniform float u_cellSizepx;
uniform vec2 u_gridOffset;
uniform int u_msaa;
uniform vec4 u_backgroundColor;
//uniform float u_squareZoomMaxRadius; //maxRadiusSq * u_zoom * u_zoom
//uniform float u_squareZoomMaxRadius08; //0.000277 * u_zoom * u_zoom // (0.020833*0.8)^2
//uniform float u_link_r; // 0.020833 * u_zoom

//const float cellSize = 0.041666 / 2.0; //Размер шарика
const float maxRadiusSq = 0.000434;// (0.020833^2) pre-calculated
const float subsRadiusSq = 0.000027;// pre-calculated

const int nullID = 99999999;// нулевой id клетки

// ITS CONST
vec4 world_pos_transform = vec4(0.);

ivec2 i_directs[] = ivec2[](
ivec2(-1, -1), ivec2(0, -1), ivec2(1, -1),
ivec2(-1, 0), ivec2(0, 0), ivec2(1, 0),
ivec2(-1, 1), ivec2(0, 1), ivec2(1, 1)
);

ivec2 i_directs2[] = ivec2[](
ivec2(0, 0), ivec2(-1, -1), ivec2(-1, 0), ivec2(0, -1), // -1 -1
ivec2(0, 0), ivec2(1, -1), ivec2(1, 0), ivec2(0, -1), //  1 -1
ivec2(0, 0), ivec2(-1, 1), ivec2(-1, 0), ivec2(0, 1), // -1  1
ivec2(0, 0), ivec2(1, 1), ivec2(1, 0), ivec2(0, 1)//  1  1
);

in vec2 win_position;
out vec4 fragColor;

float sdCapsule(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0., 1.);
    return length(pa - ba * h);
}


struct CellData {
    vec2 position;
    vec2 velocity;
    float energy;
    vec3 color;
    int mode;
    int type;
};

float getFloat(int index) {
    ivec2 texCoord = ivec2((index >> 2) & 511, index >> 11);
    return texelFetch(u_floatTexture, texCoord, 0)[index & 3];
}

int last_index = nullID;
vec4 last_data;
float getFloat1(int index) {
    int dev_ind = index >> 2;
    if (dev_ind != last_index) {
        last_index = dev_ind;
        ivec2 texCoord = ivec2(dev_ind & 255, index >> 10);
        last_data = texelFetch(u_floatTexture, texCoord, 0);
    }
    return last_data[index & 3];
}

vec3 getPheromones(ivec2 texCoord) {
    // TODO: interpolate between neighboring grid cells
    return texelFetch(u_pheromoneFloatTexture, texCoord, 0).rgb;
}

void readCellData(inout CellData current, int base) {
    current.position = vec2(getFloat(base + 1), getFloat(base + 2));
    // TODO: БЛЯТЬ КАКОГО ХУЯ // TODO: WHAT THE FUCK
    current.position = mix(world_pos_transform.xy, world_pos_transform.zw, current.position);
    current.velocity = vec2(getFloat(base + 3), getFloat(base + 4));
    current.energy = getFloat(base + 5);
    current.color = vec3(getFloat(base + 6), getFloat(base + 7), getFloat(base + 8));
    current.mode = int(getFloat(base + 9));
}

void readGridCell(
ivec2 cellivec, vec2 w_pos, inout float clos_dist1, inout float clos_dist2,
inout int closestID, inout int secondClosestID, inout vec3 baseColor) {
    int base = cellivec.x;
    if (base == 1) return;
    int total = base + cellivec.y;
    while (base < total) {
        float type = getFloat(base);

        if (type == 1.0 && base + 9 < total) {
            vec2 position = vec2(getFloat(base + 1), getFloat(base + 2));
            // TODO: БЛЯТЬ КАКОГО ХУЯ // TODO: WHAT THE FUCK
            position = mix(world_pos_transform.xy, world_pos_transform.zw, position);
            vec2 delta = position - w_pos;
            float distSq = dot(delta, delta);

            if (distSq < 0.25 && distSq < clos_dist2)//Размер шарика //Ball size
            if (distSq < clos_dist1)
            clos_dist2 = clos_dist1, clos_dist1 = distSq,
            secondClosestID = closestID, closestID = base;
            else
            clos_dist2 = distSq, secondClosestID = base;
            base += 10;
        }
        else if (type == 2.0) {
            base += 11;
        }
        else if (type == 3.0) {
            /// vec2 pos = vec2(getFloat(base + 1), getFloat(base + 2)) / u_screenSize;
            /// pos.y *= u_aspectRatio;
            /// vec2 delta = pos - w_pos;
            /// if (dot(delta, delta) < subsRadiusSq * u_zoom * u_zoom) {
            ///    vec3 subColor = vec3(getFloat(base + 4), getFloat(base + 5), getFloat(base + 6));
            ///    baseColor = mix(baseColor, subColor, float(baseColor == vec3(0.)) * 0.5);
            /// }
            base += 7;
        }
        else break;
    }
}

void readGridCellLinks(ivec2 cellivec, vec2 w_pos, inout vec4 color) {
    int base = cellivec.x;
    if (base == 1) return;
    int total = base + cellivec.y;

    while (base < total) {
        float type = getFloat(base);

        if (type == 1.0 && base + 11 < total) {
            base += 10;
        } else if (type == 2.0) {
            vec2 c1     = vec2(getFloat(base + 1), getFloat(base + 2));
            vec2 c2     = vec2(getFloat(base + 3), getFloat(base + 4));
            // TODO: БЛЯТЬ КАКОГО ХУЯ // TODO: WHAT THE FUCK
            c1 = mix(world_pos_transform.xy, world_pos_transform.zw, c1);
            c2 = mix(world_pos_transform.xy, world_pos_transform.zw, c2);

            vec3 color1    = vec3(getFloat(base + 5), getFloat(base + 6), getFloat(base + 7));
            vec3 color2    = vec3(getFloat(base + 8), getFloat(base + 9), getFloat(base + 10));

            // получаем точку ближайшую на прямой // get the closest point on the line
            vec2 ap = w_pos - c1;
            vec2 ab = c2 - c1;
            float k = dot(ap, ab) / dot(ab, ab);
            vec2 clos = c1 + k * ab;// ближайшая // closest
            float k1 = mix(smoothstep(0.5, 0., k), smoothstep(0.5, 1., k), step(0.5, k));

            if (k > 0. && k < 1. && distance(w_pos, clos) < k1 * 0.2 + 0.3) {
                float w = k1 + 0.1;
                color.rgb += mix(color1, color2, k) * (0.6 - k1 * 0.2) * w;
                color.a += w;
            }

            base += 11;
        } else if (type == 3.0) { base += 7; }
        else { break; }
    }
}

vec3 type_2_color[] = vec3[](
vec3(0., 0., 1.), // 0
vec3(0., 0., 0.), // 1
vec3(0., 1., 1.), // 2
vec3(0., 1., 0.), // 3
vec3(1., 0., 1.), // 4
vec3(1., 0., 0.), // 5
vec3(1., 1., 1.), // 6
vec3(1., 1., 0.), // 7
vec3(0., 0., 0.), // 8
vec3(0., 0., 0.), // 9
vec3(0., 0., 0.), // 10
vec3(0., 0., 0.), // 11
vec3(0., 0., 0.), // 12
vec3(0., 0., 0.), // 13
vec3(0., 0., 0.), // 14
vec3(0., 0., 0.), // 15
vec3(0., 0., 0.)// 16
);

vec3 render(vec2 uv) {
    // координаты всякие // all sorts of coordinates
    vec2 coord = uv;// win_pos в диапозоне [0 .. 1]
    vec2 w_coord = mix(world_pos_transform.xy, world_pos_transform.zw, uv);// в метрике мира // in the world metric
    ivec2 gridCoord = ivec2(w_coord);
    // переменная цвета для ретурна // color variable for return
    vec3 color = vec3(u_backgroundColor.x, u_backgroundColor.y, u_backgroundColor.z);
    color += getPheromones(gridCoord);
//    color *= mix(vec3(0.1), vec3(0.1), step(1., fract(w_coord.x) + fract(w_coord.y)));
    vec3 background_color = color;

    //  float ling = cos(w_coord.x * 0.05) * sin(w_coord.y * 0.05) * 0.5 + 0.5;//mix(0., 1., step(1., fract(w_coord.x) + fract(w_coord.y)));
    //  ling *= ling;
    //  color = vec3(min(255., 0.745 + ling / 2.), 0.745 + ling / 5., 1. - ling * ling * 0.3);

    int base = texelFetch(u_intTexture, gridCoord, 0).x;
    if (base == 2)
    return color;//vec3(0.0, 0.2, 0.0);


    vec3 baseColor = vec3(0.);// какое-то легаси // some kind of legacy?


    int closestID = nullID, secondClosestID = nullID;

    vec2 for_ofs2d = step(0.5, fract(w_coord));
    int for_ofs = int(for_ofs2d.x * 4. + for_ofs2d.y * 8.);
    float clos_dist1 = 1000., clos_dist2 = 1000.;
    for (int d = for_ofs; d < for_ofs + 4; d++) {
        ivec2 cell = texelFetch(u_intTexture, gridCoord + i_directs2[d], 0).xy;
        readGridCell(cell, w_coord, clos_dist1, clos_dist2, closestID, secondClosestID, baseColor);
    }
    //for (int d = 0; d < 9; d++) {
    //  ivec2 cell = texelFetch(u_intTexture, gridCoord + i_directs[d], 0).xy;
    //  readGridCell(cell, coord, closestID, secondClosestID, baseColor);
    //}

    // загружаем данные // load data
    CellData closest, secondClosest;

    if (closestID != nullID)
    readCellData(closest, closestID);
    if (secondClosestID != nullID)
    readCellData(secondClosest, secondClosestID);



    if (closestID != nullID) {
        float dist1 = sqrt(clos_dist1) / 0.5, dist2 = sqrt(clos_dist2) / 0.5;

        //color *= dist1 * 2.0;


        float r1 = dist1;// относительный радиус до клетки 1 // relative radius to cell 1
        float r2 = dist2;
        float r = secondClosestID == nullID ? r1 : (r1 + 1. - r2);// для изгибов клеток // for cell bends

        //if (secondClosestID != nullID && dist1 > 0.2) {
        //  color = mix(color, mix(closest.color, secondClosest.color, r * 0.5) * 1.2, 0.8);
        //} else {
        //  color = mix(color, closest.color * 1.2, 0.8);
        //}
        color = closest.color;
        if (r > 0.8) {
            color *= 0.7;
        } else {
            if (closest.mode == 3 || closest.mode == 1) {
                color = background_color;
            }
        }
        vec2 core_center = closest.position + closest.velocity;
        vec2 core_delta = core_center - w_coord;
        float core_dist = length(core_delta);
        float r_core = core_dist / 0.5;
        if (r_core < 0.2 * closest.energy * 0.25) {
            //Здесь отрисовывается черный центр ядра, я хочу сдеалать так что бы он смещался на vec2 closest.velocity
            //The black center of the core is drawn here, I want to make it offset by vec2 closest.velocity
            color = vec3(0.0, 0.0, 0.0);
        }
        if (r < 0.8 && (closest.mode == 3 || closest.mode == 1)) {
            color = background_color;
        }

        //  color = mix(color, type_2_color[closest.type], 0.75);

        return color;
    }


    vec4 link_color = vec4(0.);
    for (int d = 0; d < 9; d++) {
        ivec2 cell = texelFetch(u_intTexture, gridCoord + i_directs[d], 0).xy;
        readGridCellLinks(cell, w_coord, link_color);
    }
    if (link_color.a > 0.) {
        link_color.rgb /= link_color.a;
        color = mix(color, link_color.rgb, smoothstep(-0.1, 0.3, link_color.a));
    }

    return color;
}




void main() {
    //TODO добавлено чтобы не выбрасывалось исключение неиспользованных переменных
    // TODO added to avoid throwing exceptions for unused variables
    if (u_aspectRatio == -9999.)
    return;

    uint MSAA = uint(u_msaa);

    //TODO ITS CONST
    world_pos_transform = vec4(u_gridOffset / u_cellSizepx, (u_screenSize + u_gridOffset) / u_cellSizepx);
    //TODO ITS CONST
    vec2 half_px_step = 1. / u_screenSize / float(MSAA);// шаг на 1/msaa пикселя в нормализованном экранном
    // step by 1/msaa pixel in normalized screen space

    fragColor = vec4(vec3(0.), 1.);
    uint xr, yr;
    vec2 sub_px_ofs;
    for (sub_px_ofs.x = 0., xr = 0u; xr < MSAA; sub_px_ofs.x += half_px_step.x, xr++)
    for (sub_px_ofs.y = 0., yr = 0u; yr < MSAA; sub_px_ofs.y += half_px_step.y, yr++)
    fragColor.rgb += render(win_position + sub_px_ofs);
    fragColor.rgb /= float(MSAA * MSAA);

}
