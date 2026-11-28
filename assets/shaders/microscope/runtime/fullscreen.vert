#version 300 es
precision highp float;
precision highp int;
in vec2 aCorner;void main(){gl_Position=vec4(aCorner,0.,1.);}