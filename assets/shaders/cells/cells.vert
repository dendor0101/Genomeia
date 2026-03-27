#version 300 es
precision highp float;
precision highp int;

uniform mat4 u_projTrans;
uniform sampler2D u_circlesTex;
uniform highp isampler2D u_neighborsTex;  // isampler для int
uniform int u_texWidth;

in vec2 a_position;
out vec2 v_texCoord;
out vec4 v_color;
flat out int v_instanceID;

flat out vec2 v_pos;
flat out float v_size;
flat out float v_numNeighbors;
flat out vec3 v_circleColor;
flat out float v_type;

flat out vec2 v_neighborPos[12];

const int max_neighbors = 12;

struct Circle {
    vec2 pos;
    float size;
    float numNeighbors;
    vec3 color;
    float type;
};

Circle fetchCircle(int id) {
    int base = id * 2;
    ivec2 coord0 = ivec2(base % u_texWidth, base / u_texWidth);
    ivec2 coord1 = ivec2((base + 1) % u_texWidth, (base + 1) / u_texWidth);
    vec4 d0 = texelFetch(u_circlesTex, coord0, 0);
    vec4 d1 = texelFetch(u_circlesTex, coord1, 0);
    Circle c;
    c.pos = d0.xy;
    c.size = d0.z;
    c.numNeighbors = d0.w;
    c.color = d1.rgb;
    c.type = d1.a;
    return c;
}

int fetchNeighbor(int myID, int j) {
    int base = myID * 6;
    int pixelIndex = j / 2;
    int comp = j % 2;
    ivec2 coord = ivec2((base + pixelIndex) % u_texWidth, (base + pixelIndex) / u_texWidth);
    ivec4 data = texelFetch(u_neighborsTex, coord, 0);
    return (comp == 0) ? data.r : data.g;
}

void main() {
    int instID = gl_InstanceID;
    Circle c = fetchCircle(instID);

    v_instanceID = instID;
    v_texCoord = a_position * 0.5 + 0.5;
    v_color = vec4(c.color, 1.0);

    v_pos = c.pos;
    v_size = c.size;
    v_numNeighbors = c.numNeighbors;
    v_circleColor = c.color;
    v_type = c.type;

    int num_neigh = int(c.numNeighbors);
    for (int j = 0; j < max_neighbors; j++) {
        if (j < num_neigh) {
            int nid = fetchNeighbor(instID, j);
            if (nid >= 0) {
                Circle nc = fetchCircle(nid);
                v_neighborPos[j] = nc.pos;
            } else {
                v_neighborPos[j] = c.pos;
            }
        } else {
            v_neighborPos[j] = c.pos;
        }
    }

    vec2 offsetPos = a_position * 20.0 + c.pos;
    gl_Position = u_projTrans * vec4(offsetPos, 0.0, 1.0);
}
