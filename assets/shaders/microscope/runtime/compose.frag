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

uniform sampler2D uTissueField;
uniform vec4 uFieldBounds;
uniform vec2 uFieldTexel;
uniform float uReliefHeight;
uniform float uCohesion;
vec3 tissueField(vec2 p){
  vec4 v=texture(uTissueField,(p-uFieldBounds.xy)/uFieldBounds.zw);
  float distance=(dot(v.rg,vec2(65280.,255.))/65535.-.5)*1.5;
  float height=dot(v.ba,vec2(65280.,255.))/65535.*.08;
  height*=smoothstep(0.,max(uFieldTexel.x,uFieldTexel.y)*1.2,-distance);
  return vec3(distance,height,0.);
}
float reliefDepth(float height,float layer){return height*uReliefHeight*(.84-.5*layer);}
vec2 reliefGradient(vec2 p,float stepWorld){
  return vec2(tissueField(p+vec2(stepWorld,0.)).y-tissueField(p-vec2(stepWorld,0.)).y,
              tissueField(p+vec2(0.,stepWorld)).y-tissueField(p-vec2(0.,stepWorld)).y)/(2.*stepWorld);
}

uniform sampler2D uMap,uBehind,uGenomeTint;
uniform vec2 uRes,uCam,uSpan;
uniform float uColorFilter,uTintStrength,uRefraction,uPhase,uInspect,uMode,uPixelRatio;
uniform float uDarkContrast,uCA,uFocus,uAperture,uScale;
vec4 S(vec2 off){return texture(uMap,(gl_FragCoord.xy+off)/uRes);}
vec3 opticalAbsorption(vec4 c){return max(c.g,0.)*vec3(1.8)+max(c.b,0.)*vec3(2.3,.3,3.1)+max(-c.b,0.)*vec3(.2,1.7,2.7);}
void main(){
  vec2 uv=gl_FragCoord.xy/uRes,wp=uCam+(uv-.5)*uSpan;vec4 c=S(vec2(0.));
  vec4 n=S(vec2(0.,1.)),s=S(vec2(0.,-1.)),e=S(vec2(1.,0.)),w=S(vec2(-1.,0.));
  float ne=S(vec2(1.,1.)).r,nw=S(vec2(-1.,1.)).r,se=S(vec2(1.,-1.)).r,sw=S(vec2(-1.,-1.)).r;
  vec2 grad=vec2(ne+2.*e.r+se-nw-2.*w.r-sw,nw+2.*n.r+ne-sw-2.*s.r-se)*.125*uPixelRatio;
  float lapPhase=(n.a+s.a+e.a+w.a-4.*c.a)*uPixelRatio*uPixelRatio;
  vec2 offset=clamp(grad*uRefraction*45.,vec2(-6.),vec2(6.))*uPixelRatio/uRes;
  vec4 background=texture(uBehind,uv+offset);vec3 behind=background.rgb;
  vec3 absorption=opticalAbsorption(c);
  // Раньше uCA не передавалась в этот проход: менялась только взвесь позади тела.
  // Теперь RGB действительно снимаются с разным смещением. Верхний правый край
  // получает синюю кайму, противоположный — тёплую; сплошной синий контур не рисуется.
  if(uCA>.001&&c.r>.002){
    float height=tissueField(wp).y;
    vec2 hg=reliefGradient(wp,max(uScale,max(uFieldTexel.x,uFieldTexel.y)));
    float steep=smoothstep(.025,.18,length(hg)*uReliefHeight);
    float surfaceZ=.5+.0018*wp.x+.0012*wp.y+reliefDepth(height,4.);
    float focused=exp(-pow((surfaceZ-uFocus)*uAperture/2.6,2.));
    vec2 split=vec2(.707107)*uCA*(.65+3.8*steep*focused)*uPixelRatio;
    vec4 red=S(-split),blue=S(split);
    absorption.r=opticalAbsorption(red).r;absorption.b=opticalAbsorption(blue).b;
    vec2 dispersion=offset*uCA*.18;
    behind.r=texture(uBehind,uv+offset-dispersion).r;
    behind.b=texture(uBehind,uv+offset+dispersion).b;
  }
  vec3 transmission=exp(-absorption*uDarkContrast);
  float halo=clamp(-lapPhase*(12.+uPhase*8.),-.18,.18);if(halo<0.)halo*=uDarkContrast;
  vec3 col=behind*transmission+halo;
  if(uMode>.5)col=behind*transmission+vec3(.64,.68,.74)*(c.g*.40+length(grad)*1.9)+abs(halo)*.7;
  vec4 genotype=texture(uGenomeTint,(wp-uFieldBounds.xy)/uFieldBounds.zw);
  col*=mix(vec3(1.),clamp(genotype.rgb,0.,1.),uColorFilter*uTintStrength*genotype.a*smoothstep(.015,.10,c.r));
  if(uInspect>.5&&uInspect<1.5)col=vec3(1.-exp(-c.r*1.6));
  if(uInspect>1.5&&uInspect<2.5)col=vec3(dot(transmission,vec3(.2126,.7152,.0722))*background.a);
  if(uInspect>2.5&&uInspect<3.5)col=vec3(clamp(.5+.32*c.a/max(c.r,.001),0.,1.));
  if(uInspect>3.5)col=vec3(clamp(tissueField(wp).y/.06,0.,1.));
  outColor=vec4(max(col,0.),.5);
}