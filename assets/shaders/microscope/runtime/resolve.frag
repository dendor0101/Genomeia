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
// Подъём ткани над плоскостью фокуса (террейн), 0..1: вес окраски поля, в живой игре окраска нейтральна.
uniform sampler2D uTissueLift;
// На сколько террейн выше фокуса: при uAperture = 176 (масштаб 1) размытие около 6 пикселей.
const float TERRAIN_DEFOCUS=.03;
vec3 tissueField(vec2 p){
  vec2 at=(p-uFieldBounds.xy)/uFieldBounds.zw;
  vec4 v=texture(uTissueField,at);
  float distance=(dot(v.rg,vec2(65280.,255.))/65535.-.5)*1.5;
  float height=dot(v.ba,vec2(65280.,255.))/65535.*.08;
  height*=smoothstep(0.,max(uFieldTexel.x,uFieldTexel.y)*1.2,-distance);
  return vec3(distance,height,texture(uTissueLift,at).a);
}
float reliefDepth(float height,float layer){return height*uReliefHeight*(.84-.5*layer);}
vec2 reliefGradient(vec2 p,float stepWorld){
  return vec2(tissueField(p+vec2(stepWorld,0.)).y-tissueField(p-vec2(stepWorld,0.)).y,
              tissueField(p+vec2(0.,stepWorld)).y-tissueField(p-vec2(0.,stepWorld)).y)/(2.*stepWorld);
}

uniform sampler2D uAccum,uA;
uniform vec2 uRes,uCam,uSpan;
uniform float uScale,uLayer,uFocus,uAperture,uContourWidth;
void main(){
  vec2 uv=gl_FragCoord.xy/uRes,p=uCam+(uv-.5)*uSpan;
  float plane=.5+.0018*p.x+.0012*p.y;
  vec3 specimen=tissueField(p);float field=specimen.x,height=specimen.y;
  float edgeField=mix(texture(uA,uv).r,field,uCohesion);
  // Билинейная интерполяция не должна оставлять толщину за нулём силуэта.
  height*=smoothstep(0.,max(uFieldTexel.x,uFieldTexel.y)*1.2,-edgeField);
  float coverage=1.-smoothstep(-uScale*1.1,uScale*1.1,edgeField);
  vec4 c=texture(uAccum,uv);
  c.rgb*=smoothstep(0.,.012,height);
  float dome=clamp(height/.045,0.,1.35),inside=smoothstep(0.,.045,-field);
  float micro=c.r>1e-6?clamp(c.a/c.r,-.004,.004):0.;
  float depth=plane+reliefDepth(height,uLayer);
  depth=mix(depth,uFocus+TERRAIN_DEFOCUS,specimen.z);
  if(uLayer<.5||uLayer>3.5){
    // Наружная оболочка, а не кольца отдельных клеток. Толщина линии растёт только
    // там, где крутая поверхность попала в диапазон фокуса.
    vec2 gradient=reliefGradient(p,max(uScale, max(uFieldTexel.x,uFieldTexel.y)));
    float steep=smoothstep(.035,.19,length(gradient)*uReliefHeight);
    float focused=exp(-pow((depth-uFocus)*uAperture/2.6,2.));
    float width=max(.0002,max(.0025,uScale*(.65+2.25*steep*focused))*uContourWidth);
    float cuticle=exp(-pow(field/width,2.))*min(1.,uContourWidth);
    c.r+=.11*dome*uReliefHeight+cuticle*.025;
    c.g+=.015*dome+cuticle*(.075+.36*steep*focused);
  }else{
    depth+=micro*min(1.,height/.025)*uReliefHeight;
    c.r+=.018*dome*inside;c.g+=.012*dome*inside;
  }
  outColor=vec4(c.rgb*coverage,depth-uFocus);
}