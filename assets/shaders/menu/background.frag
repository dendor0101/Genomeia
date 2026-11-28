#version 300 es
precision highp float;
in vec2 v_texCoord;
uniform sampler2D uScene;
uniform vec2 uStep;
out vec4 color;
void main() {
    vec3 sum = texture(uScene, v_texCoord).rgb * 4.;
    for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
        if (x != 0 || y != 0) {
            float weight = (x == 0 || y == 0) ? 2. : 1.;
            sum += texture(uScene, v_texCoord + vec2(float(x), float(y)) * uStep).rgb * weight;
        }
    }
    color = vec4(sum / 16., 1.);
}
