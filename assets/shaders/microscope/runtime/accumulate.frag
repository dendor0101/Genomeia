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

uniform sampler2D uMap;uniform vec2 uRes;uniform float uFocus,uPhase;
void main(){
  vec4 c=texture(uMap,gl_FragCoord.xy/uRes);
  // Небольшое смещение задаётся контролем фазового ореола. Тот же срез даёт противоположный фазовый контраст по разные стороны фокуса.
  float phaseWeight=clamp(c.a*38.+uPhase*.35,-1.5,1.5);
  outColor=vec4(c.rgb,c.r*phaseWeight);
}