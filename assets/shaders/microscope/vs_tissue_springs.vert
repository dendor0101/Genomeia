#version 300 es
precision highp float;
precision highp int;
uniform highp sampler2D uSprings,uLiveData;
uniform int uBodyOffset;
uniform vec2 uCam,uSpan;
void main(){
    int w=textureSize(uSprings,0).x;
    vec2 edge=texelFetch(uSprings,ivec2(gl_InstanceID%w,gl_InstanceID/w),0).xy;
    int i=uBodyOffset+int(gl_VertexID==0?edge.x:edge.y);
    int rows=textureSize(uLiveData,0).y/6;
    vec2 p=texelFetch(uLiveData,ivec2(i%256,i/256+rows),0).xy-uCam;
    gl_Position=vec4(p/uSpan*2.,0.,1.);
}
