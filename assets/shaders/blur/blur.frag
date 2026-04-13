#ifdef GL_ES
precision mediump float;
#endif

varying vec2 v_texCoord;
uniform sampler2D u_texture;
uniform float u_blurAmount;
uniform vec2 u_resolution;
//uniform float u_chromaticAberration;   // сила хроматической аберрации в пикселях (0 = выкл, 3–10 даёт красивый эффект)

void main() {
    vec2 texelSize = 1.0 / u_resolution;
    float blurRadius = u_blurAmount;
    float caAmount = /*u_chromaticAberration*/4.5;

    // Radial offset for chromatic aberration (масштабируется с расстоянием от центра)
    // *2.0 нужно, чтобы на краю экрана смещение было ровно caAmount пикселей
    vec2 caDir = (v_texCoord - vec2(0.5)) * (caAmount * 2.0) * texelSize;

    vec2 uvRed   = v_texCoord + caDir;
    vec2 uvGreen = v_texCoord;
    vec2 uvBlue  = v_texCoord - caDir;

    // 9-tap Gaussian blur для КАЖДОГО канала отдельно (чтобы блюр красиво ложился на аберрацию)
    vec4 blurRed = vec4(0.0);
    blurRed += texture2D(u_texture, uvRed + vec2(-1.0, -1.0) * texelSize * blurRadius) * 0.0625;
    blurRed += texture2D(u_texture, uvRed + vec2( 0.0, -1.0) * texelSize * blurRadius) * 0.125;
    blurRed += texture2D(u_texture, uvRed + vec2( 1.0, -1.0) * texelSize * blurRadius) * 0.0625;

    blurRed += texture2D(u_texture, uvRed + vec2(-1.0,  0.0) * texelSize * blurRadius) * 0.125;
    blurRed += texture2D(u_texture, uvRed + vec2( 0.0,  0.0) * texelSize * blurRadius) * 0.25;
    blurRed += texture2D(u_texture, uvRed + vec2( 1.0,  0.0) * texelSize * blurRadius) * 0.125;

    blurRed += texture2D(u_texture, uvRed + vec2(-1.0,  1.0) * texelSize * blurRadius) * 0.0625;
    blurRed += texture2D(u_texture, uvRed + vec2( 0.0,  1.0) * texelSize * blurRadius) * 0.125;
    blurRed += texture2D(u_texture, uvRed + vec2( 1.0,  1.0) * texelSize * blurRadius) * 0.0625;

    vec4 blurGreen = vec4(0.0);
    blurGreen += texture2D(u_texture, uvGreen + vec2(-1.0, -1.0) * texelSize * blurRadius) * 0.0625;
    blurGreen += texture2D(u_texture, uvGreen + vec2( 0.0, -1.0) * texelSize * blurRadius) * 0.125;
    blurGreen += texture2D(u_texture, uvGreen + vec2( 1.0, -1.0) * texelSize * blurRadius) * 0.0625;

    blurGreen += texture2D(u_texture, uvGreen + vec2(-1.0,  0.0) * texelSize * blurRadius) * 0.125;
    blurGreen += texture2D(u_texture, uvGreen + vec2( 0.0,  0.0) * texelSize * blurRadius) * 0.25;
    blurGreen += texture2D(u_texture, uvGreen + vec2( 1.0,  0.0) * texelSize * blurRadius) * 0.125;

    blurGreen += texture2D(u_texture, uvGreen + vec2(-1.0,  1.0) * texelSize * blurRadius) * 0.0625;
    blurGreen += texture2D(u_texture, uvGreen + vec2( 0.0,  1.0) * texelSize * blurRadius) * 0.125;
    blurGreen += texture2D(u_texture, uvGreen + vec2( 1.0,  1.0) * texelSize * blurRadius) * 0.0625;

    vec4 blurBlue = vec4(0.0);
    blurBlue += texture2D(u_texture, uvBlue + vec2(-1.0, -1.0) * texelSize * blurRadius) * 0.0625;
    blurBlue += texture2D(u_texture, uvBlue + vec2( 0.0, -1.0) * texelSize * blurRadius) * 0.125;
    blurBlue += texture2D(u_texture, uvBlue + vec2( 1.0, -1.0) * texelSize * blurRadius) * 0.0625;

    blurBlue += texture2D(u_texture, uvBlue + vec2(-1.0,  0.0) * texelSize * blurRadius) * 0.125;
    blurBlue += texture2D(u_texture, uvBlue + vec2( 0.0,  0.0) * texelSize * blurRadius) * 0.25;
    blurBlue += texture2D(u_texture, uvBlue + vec2( 1.0,  0.0) * texelSize * blurRadius) * 0.125;

    blurBlue += texture2D(u_texture, uvBlue + vec2(-1.0,  1.0) * texelSize * blurRadius) * 0.0625;
    blurBlue += texture2D(u_texture, uvBlue + vec2( 0.0,  1.0) * texelSize * blurRadius) * 0.125;
    blurBlue += texture2D(u_texture, uvBlue + vec2( 1.0,  1.0) * texelSize * blurRadius) * 0.0625;

    // Собираем финальный цвет: каждый канал берётся из своего смещённого блюра
    // Альфа — от центрального (зелёного) канала, как в обычном блюре
    vec4 color = vec4(blurRed.r, blurGreen.g, blurBlue.b, blurGreen.a);

    gl_FragColor = color;
}
