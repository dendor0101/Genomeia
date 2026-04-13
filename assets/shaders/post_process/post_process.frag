#version 300 es
precision highp float;

varying vec2 v_texCoord;
uniform sampler2D u_texture;
uniform sampler2D u_linesTexture;     // ← НОВОЕ
uniform vec2 u_resolution;
uniform vec2 u_cameraPos;             // ← НОВОЕ (worldX, worldY)
uniform mat4 u_invProj;
uniform float u_parallaxStrength;     // ← НОВОЕ (сила параллакса)

void main() {

    vec4 texture = texture2D(u_texture, v_texCoord) * 1.4;

    // размер одного пикселя
    vec2 texel = 1.0 / u_resolution;

    // 4 сэмпла (Sobel)
    float p00 = dot(texture2D(u_texture, v_texCoord - texel).rgb, vec3(0.299, 0.587, 0.114));
    float p11 = dot(texture2D(u_texture, v_texCoord + texel).rgb, vec3(0.299, 0.587, 0.114));
    float p10 = dot(texture2D(u_texture, v_texCoord + vec2(texel.x, -texel.y)).rgb, vec3(0.299, 0.587, 0.114));
    float p01 = dot(texture2D(u_texture, v_texCoord + vec2(-texel.x, texel.y)).rgb, vec3(0.299, 0.587, 0.114));

    float gx = p00 - p11;
    float gy = p10 - p01;

    float edge = length(vec2(gx, gy));
    edge = /*1.0 - */smoothstep(0.0, 0.12, edge);

    vec4 background = vec4(1.0, 0.969, 0.855, 1.0);

    // ==================== PARALLAX LINES (ТЕПЕРЬ В МИРОВЫХ КООРДИНАТАХ) ====================
    vec2 ndc = v_texCoord * 2.0 - 1.0;

    // Преобразуем NDC → мировые координаты через инверсную матрицу камеры
    vec4 clipPos = vec4(ndc, 0.0, 1.0);
    vec4 worldHom = u_invProj * clipPos;
    vec2 worldPos = worldHom.xy;                     // для ortho w = 1

    // Параллакс: background двигается медленнее основной сцены
    vec2 samplePos = worldPos - u_cameraPos * (1.0 - u_parallaxStrength);

    float tileWorldSize = 240.0;                     // ← НАСТРАИВАЙ: больше = крупнее тайлы, меньше видно одновременно
    vec2 linesUV = samplePos / tileWorldSize;

    vec4 linesSample = texture2D(u_linesTexture, linesUV);

    float linesFactor = 1.0 - linesSample.r; // чёрный=1, белый=0
    vec4 finalBackground = background * linesFactor;
    // =================================================================================

    vec4 textureMixBackground = mix(finalBackground, texture, 0.1875);

    float gray = (texture.r + texture.g + texture.b) / 3.0;
    gray = step(0.06, gray);

    vec4 result = mix(finalBackground, textureMixBackground, gray);

    float white = 1.0 - edge;

    //Lerp white
    float p1 = 0.0;
    float p2 = 0.98;
    vec4 color1 = vec4(0.678, 0.569, 0.435, 1.0);
    vec4 color2 = vec4(1.0);
    vec4 color = mix(color1, color2, (white - p1) / (p2 - p1));

    vec4 colorA = color * result;
    vec4 pastelColor = colorA/* * edge*/;

    // --- ВИНЬЕТКА ---
    vec2 uv = v_texCoord;
    vec2 pos = uv * 2.0 - 1.0;
    float aspect = u_resolution.x / u_resolution.y;
    pos.x *= aspect;
    float dist = length(pos);
    float maxDist = length(vec2(aspect, 1.0));
    float normDist = dist / maxDist;
    float radius = 0.9;
    float softness = 0.8;
    float vignette = smoothstep(radius, radius - softness, normDist);

    vec4 plugColor = pastelColor * vignette * 0.000001;

    gl_FragColor = pastelColor * vignette/*colorA + plugColor*/;
}
