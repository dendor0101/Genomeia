#version 300 es
precision highp float;

in vec4 a_position;
out vec2 win_position;

void main() {
    gl_Position = a_position; // a_pos в диапозоне [-1 .. +1]

    win_position = (a_position.xy * 0.5 + 0.5); // win_pos в диапозоне [0 .. 1]
}
