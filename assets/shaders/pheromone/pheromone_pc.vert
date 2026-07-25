#version 300 es
precision highp float;
precision highp int;

layout (location = 0) in vec2 a_position;

// camera.projection — positions in u_data are camera-relative (world - cameraPos)
uniform mat4 u_projTrans;
uniform float u_K;
uniform float u_P;

// RGBA32F data texture: 2 texels per pheromone instance
// texel0: x, y, A, colorR   (x,y relative to camera)
// texel1: colorG, colorB, pad, pad
uniform sampler2D u_data;
uniform int u_texWidth;

out vec2 v_localUV;
out float v_A;
out float v_radius;
flat out vec3 ex_Color;

void main() {
    int id = gl_InstanceID;

    // 2 texels per instance; width is even so both stay on the same row
    int base = id * 2;
    int texY = base / u_texWidth;
    int texX0 = base - texY * u_texWidth;
    int texX1 = texX0 + 1;

    vec4 t0 = texelFetch(u_data, ivec2(texX0, texY), 0);
    vec4 t1 = texelFetch(u_data, ivec2(texX1, texY), 0);

    vec2 camRelativePos = t0.xy;
    v_A = t0.z;
    ex_Color = vec3(t0.w, t1.x, t1.y);

    float squaredRadius = max((v_A / u_P - 1.0) / u_K, 0.0);
    float radius = sqrt(squaredRadius);

    vec2 offset = a_position * radius;

    gl_Position = u_projTrans * vec4(camRelativePos + offset, 0.0, 1.0);

    v_localUV = a_position;
    v_radius  = squaredRadius;
}
