#version 300 es
precision highp float;
precision highp int;
uniform highp sampler2D uData;
uniform vec2 uCam,uSpan;
uniform float uPad;
const vec2 corners[6]=vec2[6](vec2(-1,-1),vec2(1,-1),vec2(1,1),vec2(-1,-1),vec2(1,1),vec2(-1,1));
void main(){
    ivec2 size=textureSize(uData,0);int id=gl_InstanceID;
    vec4 cell=texelFetch(uData,ivec2(id%size.x,id/size.x+size.y/6),0);
    vec2 p=cell.xy+corners[gl_VertexID]*(cell.z*4.+uPad);
    gl_Position=vec4((p-uCam)*2./uSpan,0.,1.);
}
