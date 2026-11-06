#version 300 es
precision highp float;
precision highp int;
layout(location=0) out vec4 outColor;
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
vec2 dataUV(float id,float row,float n){float w=min(n,256.),rows=ceil(n/w);return (vec2(mod(max(id,0.),w),floor(max(id,0.)/w)+row*rows)+.5)/vec2(w,rows*6.);}

uniform sampler2D uMap;uniform vec2 uRes,uDirection;
uniform float uFocus,uAperture,uPixelRatio,uTissueBlur;
void main(){
  vec2 uv=gl_FragCoord.xy/uRes;vec4 c=texture(uMap,uv);
  float sigma=max(.35,clamp(.55+abs(c.a)*uAperture,.55,7.)*uPixelRatio);
  sigma=sqrt(sigma*sigma+pow(uTissueBlur*uPixelRatio,2.));
  vec3 sum=c.rgb;float weight=1.,inv=.5/(sigma*sigma);
  for(int i=0;i<8;i++){
    float d=1.+float(i)*2.,a=exp(-d*d*inv),b=exp(-(d+1.)*(d+1.)*inv),w=a+b;
    if(w<.00005)break;
    vec2 stepUV=uDirection*(d+b/w)/uRes;
    sum+=(texture(uMap,uv+stepUV).rgb+texture(uMap,uv-stepUV).rgb)*w;weight+=2.*w;
  }
  outColor=vec4(sum/weight,c.a);
}