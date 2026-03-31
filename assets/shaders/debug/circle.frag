#version 320 es
precision highp float;

in vec2 ex_Quad;
flat in vec2 ex_Centroid;
flat in vec2 ex_Velocity;
flat in vec3 ex_Color;
flat in float ex_R;
flat in float ex_R_2;

out vec4 fragColor;

void main() {
    vec2 diff = ex_Quad - ex_Centroid;
    float dist2 = dot(diff, diff);

    if (dist2 > ex_R_2) discard;

    float normalized = dist2 / ex_R_2;

    // === Аппроксимация "сферы" (без дорогого sqrt)
    float z = ex_R * (1.0 - normalized * 0.5);

    vec3 normal = normalize(vec3(diff, z));




    // === Свет
    vec3 lightDir = normalize(vec3(0.4, 0.6, 1.0));
    vec3 viewDir  = vec3(0.0, 0.0, 1.0);
    float diffuse = max(dot(normal, lightDir), 0.0);
    float ambient = 0.2;
    // Specular (дешёвый)
    vec3 halfDir = normalize(lightDir + viewDir);
    float spec = pow(max(dot(normal, halfDir), 0.0), 24.0);
    // Rim light
    float rim = pow(1.0 - max(dot(normal, viewDir), 0.0), 2.0);
    vec3 color = ex_Color * (ambient + diffuse);
    color += spec * 0.25;
    color += rim * 0.15;



    // === Лёгкий noise (дешёвый)
    float noise = fract(sin(dot(ex_Quad, vec2(12.9898,78.233))) * 43758.5453);
    color *= 0.95 + noise * 0.1;





    // === Граница клетки (эффект Вороного)
    float border = smoothstep(0.75, 1.0, normalized);
    color = mix(color, vec3(0.0), border * 0.25);





    // === Инерция ядра
    vec2 shiftedCenter = ex_Centroid + ex_Velocity * smoothstep(0.0, 1.0, normalized);
    vec2 coreDiff = ex_Quad - shiftedCenter;
    float coreDist2 = dot(coreDiff, coreDiff);
    if (coreDist2 < 0.04 * ex_R_2) {
        color = vec3(0.0);
    }




    // === Мягкие края (антиалиас)
    float edge = smoothstep(ex_R_2, ex_R_2 * 0.9, dist2);
    float alpha = edge;
    fragColor = vec4(color, alpha);



    // === Depth (лучше чем раньше)
    float depth = 1.0 - (z / ex_R);
    gl_FragDepth = depth;
}
