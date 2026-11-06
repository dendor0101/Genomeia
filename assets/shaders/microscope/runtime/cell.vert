#version 300 es
precision highp float;
precision highp int;

in vec2 aCorner;in vec4 aI0,aI1,aI2;
uniform vec2 uCam,uSpan;
out vec2 vLocal;out vec4 vCell;out vec3 vFiber;
void main(){
  float c=cos(aI1.w),s=sin(aI1.w);
  vLocal=mat2(c,-s,s,c)*aCorner*1.5;
  vCell=vec4(aI2.x,aI2.z,aI0.z,aI2.y);
  vFiber=vec3(aI0.xy+aCorner*aI0.z*1.5,aI2.w);
  gl_Position=vec4((aI0.xy+aCorner*aI0.z*1.5-uCam)*2./uSpan,0.,1.);
}