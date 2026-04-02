#version 320 es
precision highp float;

in vec2 ex_Quad;
flat in vec2 ex_Centroid;
flat in vec2 ex_Velocity;
flat in vec3 ex_Color;
flat in float ex_R;
flat in float ex_R_2;
in vec2 ex_UV;

out vec4 fragColor;

uniform sampler2D u_texture;
uniform float u_textureScale;

void main() {
    vec2 diff = ex_Quad - ex_Centroid;
    float dist2 = dot(diff, diff);
    if (dist2 > ex_R_2) discard;

    float normalized = dist2 / ex_R_2;

    // 3D-нормаль
    float z = ex_R * (1.0 - normalized * 0.5);
    vec3 normal = normalize(vec3(diff, z));

    // === ОСВЕЩЕНИЕ (стеклянный эффект) ===
    vec3 lightDir = normalize(vec3(0.4, 0.6, 1.0));
    vec3 viewDir  = vec3(0.0, 0.0, 1.0);
    float diffuse = max(dot(normal, lightDir), 0.0);
    float ambient = 0.5;
    vec3 halfDir = normalize(lightDir + viewDir);
    float spec = pow(max(dot(normal, halfDir), 0.0), 36.0);
    float rim = pow(1.0 - max(dot(normal, viewDir), 0.0), 3.2);

    // === ТЕКСТУРА: ЧЁРНО-БЕЛАЯ + УЧИТЫВАЕМ АЛЬФУ ===
    vec2 refraction = normal.xy * 0.13 * (1.0 - normalized);
    vec2 distortedUV = ex_UV * u_textureScale + refraction;

    vec4 texColor = texture(u_texture, distortedUV);

    // 1. Преобразуем в grayscale (убираем зелёный оттенок)
    float gray = dot(texColor.rgb, vec3(0.299, 0.587, 0.114));

    // 2. Умножаем на alpha текстуры → прозрачные области полностью исчезают
    float textureMask = gray * texColor.a;

    // === Базовый цвет от текстуры (нейтральный) ===
    vec3 textureContribution = vec3(textureMask);

    // === Освещение поверх текстуры ===
    vec3 litTexture = textureContribution * (ambient + diffuse * 1.4)
    + spec * 0.65 * textureContribution
    + rim * 0.55 * textureContribution;

    // === Цвет клетки (теперь полностью контролирует оттенок) ===
    vec3 cellColor = ex_Color * 0.92;
    vec3 finalColor = mix(litTexture, cellColor, 0.3);

    // === Вороной-граница ===
    float border = smoothstep(0.75, 1.0, normalized);
    finalColor = mix(finalColor, vec3(0.0), border * 0.4);

    // Тёмное ядро
    if (dot(diff, diff) < 0.04 * ex_R_2) {
        finalColor *= 0.65;
    }

    // Полная непрозрачность клетки (соседи не просвечивают)
    fragColor = vec4(finalColor, 1.0);

    // Оригинальный depth для 3D-эффекта Вороного
    gl_FragDepth = 1.0 - (z / ex_R);
}
