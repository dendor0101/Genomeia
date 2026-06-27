#version 150

uniform int u_mode; //defined in ChannelBar.java
uniform float u_r;
uniform float u_g;
uniform float u_b;

in vec4 v_color;
in vec2 v_texCoords;

out vec4 fragColor;

void main() {
    if(u_mode == 0) fragColor = v_color * vec4(u_r, u_g, u_b, v_texCoords.s); //alpha bar
    if(u_mode == 1) fragColor = v_color * vec4(v_texCoords.s, u_g, u_b, 1.0); //r bar
    if(u_mode == 2) fragColor = v_color * vec4(u_r, v_texCoords.s, u_b, 1.0); //g bar
    if(u_mode == 3) fragColor = v_color * vec4(u_r, u_g, v_texCoords.s, 1.0); //b bar
}
