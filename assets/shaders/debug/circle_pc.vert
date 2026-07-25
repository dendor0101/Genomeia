#version 300 es
precision highp float;
precision highp int;

layout(location = 0) in vec2 a_position;

// camera.projection — positions in u_data are camera-relative (world - cameraPos)
uniform mat4 u_projTrans;

// RGBA32F data texture: 3 texels per particle
// texel0: x, y, colorR, colorG   (x,y relative to camera)
// texel1: colorB, radius, energy, cellType
// texel2: cosA, sinA, pad, pad
uniform sampler2D u_data;
uniform int u_texWidth;

out vec2 ex_Quad;
flat out vec2 ex_Centroid;
flat out vec3 ex_Color;
flat out float ex_R;
flat out float ex_R_2;
out vec2 ex_UV;
flat out float ex_AngleCos;
flat out float ex_AngleSin;
flat out float ex_Energy;
flat out int ex_cellType;

float hash(float n) {
    return fract(sin(n) * 43758.5453123);
}

void main() {
    int id = gl_InstanceID;

    // 3 texels per particle, tightly packed row-major
    // width is divisible by 3 so a particle never straddles a row
    int base = id * 3;
    int texY0 = base / u_texWidth;
    int texX0 = base - texY0 * u_texWidth;
    int texX1 = texX0 + 1;
    int texX2 = texX0 + 2;

    vec4 t0 = texelFetch(u_data, ivec2(texX0, texY0), 0);
    vec4 t1 = texelFetch(u_data, ivec2(texX1, texY0), 0);
    vec4 t2 = texelFetch(u_data, ivec2(texX2, texY0), 0);

    // Camera-relative center (near 0) — full float precision for motion & circle math
    vec2 pos = t0.xy;
    vec3 color = vec3(t0.z, t0.w, t1.x);
    float radius = t1.y;
    float energyNorm = t1.z;
    int cellType = int(t1.w + 0.5);
    float cosA = t2.x;
    float sinA = t2.y;

    ex_R = radius;
    // Match previous visual: packed energy byte/255 → *0.5 → squared
    float energy = energyNorm * 0.5;

    // Local offset of this quad vertex from particle center (exact, small numbers)
    vec2 local = a_position * ex_R;
    vec2 camRelativePos = pos + local;

    // Pass local coords to frag so ex_Quad - ex_Centroid never cancels large floats
    ex_Quad = local;
    ex_Centroid = vec2(0.0);
    ex_Color = color;
    ex_R_2 = ex_R * ex_R;
    ex_Energy = energy * energy;
    ex_UV = a_position * 0.5 + 0.5;
    ex_cellType = cellType;

    float noiseAngle = (hash(float(id)) - 0.5) * 3.0;
    float ca = cos(noiseAngle);
    float sa = sin(noiseAngle);
    float mirroredCos = cosA;
    float mirroredSin = -sinA;
    float nx = mirroredCos * ca - mirroredSin * sa;
    float ny = mirroredSin * ca + mirroredCos * sa;

    ex_AngleCos = nx;
    ex_AngleSin = ny;

    gl_Position = u_projTrans * vec4(camRelativePos, 0.0, 1.0);
}
