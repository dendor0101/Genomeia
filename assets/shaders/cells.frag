#version 300 es
precision highp float;
precision highp int;

uniform sampler2D u_circlesTex;
uniform highp isampler2D u_neighborsTex;
uniform int u_texWidth;

in vec2 v_texCoord;
in vec4 v_color;
flat in int v_instanceID;
out vec4 fragColor;

flat in vec2 v_pos;
flat in float v_size;
flat in float v_numNeighbors;
flat in vec3 v_circleColor;
flat in float v_type;

flat in vec2 v_neighborPos[12];

const int max_neighbors = 12;

void main() {
    int myID = v_instanceID;
    vec2 my_pos = v_pos;
    float radius = 20.0;
    float fragSize = 40.0;

    vec2 uv = v_texCoord - vec2(0.5);
    float dist2 = dot(uv, uv);
    float r2 = 0.244;

    float aa = fwidth(dist2) * 0.5;
    float alpha = 1.0 - smoothstep(r2 - aa, r2 + aa, dist2);

    if (alpha < 0.01) discard;

    vec2 frag_world = my_pos + uv * fragSize;
    float dist_my2 = dot(frag_world - my_pos, frag_world - my_pos);

    float min_edge_dist = 1e20;
    int closest_nid = -1;
    bool draw = true;
    int num_neigh = min(int(v_numNeighbors), max_neighbors);

    const float epsilon = 1e-4;

    for (int j = 0; j < num_neigh; j++) {
        vec2 nc_pos = v_neighborPos[j];

        vec2 delta = nc_pos - my_pos;
        float d_centers = length(delta);
        if (d_centers < 1e-4) continue;

        vec2 dir = delta / d_centers;
        vec2 midpoint = my_pos + 0.5 * delta;
        float plane_dist = dot(frag_world - midpoint, dir);

        if (plane_dist > epsilon) {  // Tolerance for fp
            draw = false;
            break;
        }

        float edge_dist = -plane_dist + epsilon;  // Bias for stability

        if (edge_dist < min_edge_dist) {
            min_edge_dist = edge_dist;
            closest_nid = j;
        }
    }

    if (!draw) discard;

    float voronoi_aa = fwidth(min_edge_dist) * 0.5;
    float blend_width = voronoi_aa * 2.0;

    float thickness2 = 1.0;
    if (v_type == 1.0) thickness2 = 2.0;
    float thickness = 1.0;
    if (v_type == 1.0) thickness = 4.0;

    float border_thickness2 = 0.1 * radius * thickness2;
    float border_thickness = 0.05 * radius * thickness;

    float voronoi_border_factor = 1.0 - smoothstep(border_thickness - voronoi_aa, border_thickness + voronoi_aa, min_edge_dist);

    float thickness_uv = border_thickness2 / fragSize;
    float r_uv = sqrt(r2);
    float inner_r = max(0.0, r_uv - thickness_uv);
    float inner_r2 = inner_r * inner_r;
    float circle_border_factor = smoothstep(inner_r2 - aa, inner_r2 + aa, dist2);

    float border_factor = max(voronoi_border_factor, circle_border_factor);

    float small_radius = 0.05 * radius * v_size;
    float small_r_uv = small_radius / fragSize;
    float small_r2 = small_r_uv * small_r_uv;
    float small_factor = 1.0 - smoothstep(small_r2 - aa, small_r2 + aa, dist2);

    vec3 base_color = v_color.rgb;

    vec3 state_color = base_color;
    if (v_type == 1.0) {
        state_color = base_color;
        base_color = vec3(26.0 / 255.0, 27.0 / 255.0, 46.0 / 255.0);
    }
    if (v_type == 0.0) state_color = vec3(0.0);
    base_color = mix(base_color, state_color, border_factor);
    base_color = mix(base_color, vec3(0.0), small_factor);
    fragColor = vec4(base_color, v_color.a * alpha);
}
