attribute vec4 a_position;
varying vec2 v_texCoord;

void main() {
    v_texCoord = (a_position.xy + 1.0) * 0.5;
    gl_Position = a_position;
}
