#version 320 es
precision highp float;

in vec2 v_texCoord;
uniform sampler2D u_texture;
//uniform sampler2D u_linesTexture;
uniform vec2 u_resolution;
//uniform vec2 u_cameraPos;
//uniform mat4 u_invProj;
//uniform float u_parallaxStrength;
uniform float u_zoom;
uniform vec3 u_backgroundColor;
uniform float u_vignetteRadius;
uniform float u_vignetteSoftness;

out vec4 fragColor;

void main() {

    vec4 textureColor = texture(u_texture, v_texCoord) * 1.4;

    // размер одного пикселя
    vec2 texel = 1.0 / u_resolution;

    // 4 сэмпла (Sobel)
    float p00 = dot(texture(u_texture, v_texCoord - texel).rgb, vec3(0.299, 0.587, 0.114));
    float p11 = dot(texture(u_texture, v_texCoord + texel).rgb, vec3(0.299, 0.587, 0.114));
    float p10 = dot(texture(u_texture, v_texCoord + vec2(texel.x, -texel.y)).rgb, vec3(0.299, 0.587, 0.114));
    float p01 = dot(texture(u_texture, v_texCoord + vec2(-texel.x, texel.y)).rgb, vec3(0.299, 0.587, 0.114));

    float gx = p00 - p11;
    float gy = p10 - p01;

    float edge = length(vec2(gx, gy));
    edge = smoothstep(0.0, u_zoom, edge);

    vec4 background = vec4(u_backgroundColor, 1.0);

    // ==================== PARALLAX LINES (ТЕПЕРЬ В МИРОВЫХ КООРДИНАТАХ) ====================
    // (your commented code left unchanged, just updated texture call for consistency)
    //    vec2 ndc = v_texCoord * 2.0 - 1.0;
    //
    //    vec4 clipPos = vec4(ndc, 0.0, 1.0);
    //    vec4 worldHom = u_invProj * clipPos;
    //    vec2 worldPos = worldHom.xy;
    //
    //    vec2 samplePos = worldPos - u_cameraPos * (1.0 - u_parallaxStrength);
    //
    //    float tileWorldSize = 240.0;
    //    vec2 linesUV = samplePos / tileWorldSize;
    //
    //    vec4 linesSample = texture(u_linesTexture, linesUV);
    //
    //    float linesFactor = 1.0 - linesSample.r;
    vec4 finalBackground = background/* * linesFactor*/;
    // =================================================================================

    vec4 textureMixBackground = mix(finalBackground, textureColor, 0.1875);

    float gray = (textureColor.r + textureColor.g + textureColor.b) / 3.0;
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
    float vignette = smoothstep(u_vignetteRadius, u_vignetteRadius - u_vignetteSoftness, normDist);

//    vec4 plugColor = pastelColor * vignette * 0.000001;

    fragColor = pastelColor * vignette/*vec4(edge) + plugColor*/;
}
