#version 300 es
precision highp float;

in vec2 v_texCoord;

uniform sampler2D u_texture;
uniform vec2 u_resolution;
// Сдвиг каналов у края кадра, в пикселях этого кадра (DistortRenderer, RenderContext.chromaticAberration).
uniform float u_caAmount;

out vec4 fragColor;

void main() {
    vec2 texelSize = 1.0 / u_resolution;
    vec2 caDir = (v_texCoord - vec2(0.5)) * (u_caAmount * 2.0) * texelSize;

    vec2 uvRed   = v_texCoord + caDir;
    vec2 uvGreen = v_texCoord;
    vec2 uvBlue  = v_texCoord - caDir;

    float r = texture(u_texture, uvRed).r;
    float g = texture(u_texture, uvGreen).g;
    float b = texture(u_texture, uvBlue).b;
    float a = texture(u_texture, uvGreen).a;

    fragColor = vec4(r, g, b, a);
}
