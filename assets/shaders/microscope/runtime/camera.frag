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

uniform sampler2D uCol, uHDof, uBot, uHBot, uDirtTex, uQ; uniform vec2 uRes;
uniform float uFocus, uCoc, uTime, uMode, uVig, uDirt;
uniform vec3 uExpo; uniform float uCamFx, uGlowR, uMask, uVolume;

uniform sampler2D uTissue;
vec3 microtex(vec2 p){ return texture(uTissue,p).rgb; }

float sq(float x){ return x*x; }
vec2 cmul(vec2 a, vec2 b){ return vec2(a.x*b.x-a.y*b.y, a.y*b.x+a.x*b.y); }                    // вместо pow(x,2.): pow с отрицательным x даёт NaN на части видеокарт
float hash11(float p){ p=fract(p*.1031); p*=p+33.33; p*=p+p; return fract(p); }
float hash12(vec2 p){ vec3 p3=fract(vec3(p.xyx)*.1031); p3+=dot(p3,p3.yzx+33.33); return fract((p3.x+p3.y)*p3.z); }
vec2 hash22(vec2 p){ vec3 p3=fract(vec3(p.xyx)*vec3(.1031,.1030,.0973)); p3+=dot(p3,p3.yzx+33.33); return fract((p3.xx+p3.yz)*p3.zy); }
float vnoise(vec2 p){ vec2 i=floor(p), f=fract(p); vec2 u=f*f*(3.-2.*f);
  return mix(mix(hash12(i),hash12(i+vec2(1.,0.)),u.x), mix(hash12(i+vec2(0.,1.)),hash12(i+vec2(1.,1.)),u.x), u.y); }
float fbm(vec2 p, float pxu){ float a=.5, s=0.;
  for(int i=0;i<5;i++){ float f=1.-smoothstep(.3,.8,pxu); s+=a*(f>0.?mix(.5,vnoise(p),f):.5);
    p=mat2(1.6,1.2,-1.2,1.6)*p+3.1; pxu*=2.; a*=.5; }
  return s/.97; }
float fbm3(vec2 p){ return (vnoise(p)*.57+vnoise(p*2.03+5.1)*.29+vnoise(p*4.1+1.7)*.14); }
vec2 worley(vec2 p){ vec2 i=floor(p), f=fract(p); float d1=8., d2=8.;
  for(int y=-1;y<=1;y++) for(int x=-1;x<=1;x++){ vec2 g=vec2(float(x),float(y)); vec2 r=g+hash22(i+g)-f; float d=dot(r,r);
    if(d<d1){d2=d1;d1=d;} else if(d<d2){d2=d;} }
  return sqrt(vec2(d1,d2)); }
mat2 rot(float a){ float c=cos(a), s=sin(a); return mat2(c,-s,s,c); }
float smax(float a,float b,float k){ float h=max(k-abs(a-b),0.)/k; return max(a,b)+h*h*k*.25; }
// тонкие завитые волокна = изолиния шума; mask делает их редкими обрывками
float fiber(vec2 p, float w, float px, float seed){
  float m=smoothstep(.74,.84,vnoise(p*.3+seed*1.7)); if(m<=0.) return 0.;
  float n=fbm3(p+seed), e=.02;
  vec2 g=vec2(fbm3(p+vec2(e,0.)+seed)-n, fbm3(p+vec2(0.,e)+seed)-n)/e;
  float d=abs(n-.5)/max(length(g),.05);
  return (1.-smoothstep(w*.5,w+px,d))*m;
}

void main(){
  vec2 uv=gl_FragCoord.xy/uRes;
  vec4 c0=texture(uCol,uv);
  if(uMask>.5){outColor=vec4(c0.rgb,1.);return;}
  vec3 col=c0.rgb;
  if(uVolume<.5){
  vec2 e=.75/uRes;                                        // 4 билинейные выборки сглаживают шум половинного размытия
  vec4 hd=.25*(texture(uHDof,uv+vec2(e.x,e.y))+texture(uHDof,uv+vec2(-e.x,e.y))+texture(uHDof,uv+vec2(e.x,-e.y))+texture(uHDof,uv-e));
  float kC=abs(c0.a-uFocus)*uCoc;
  // в фокусе — полное разрешение, в расфокусе и там, где на пиксель «пролилось» чужое боке, — размытая половина
  col=mix(c0.rgb,hd.rgb,clamp(max(smoothstep(.8,2.2,kC),hd.a*1.5),0.,1.));
  vec4 b0=texture(uBot,uv); float kb=abs(b0.a-uFocus)*uCoc;
  vec3 bot=mix(b0.rgb,texture(uHBot,uv).rgb,smoothstep(.7,2.,kb));
  col= uMode<.5 ? col*bot : col+bot;
  }
  // свечение ярких мест: 4 выборки из уже размытой половины
  vec3 glow=vec3(0.); float th=uMode<.5?.62:.22;
  for(int i=0;i<8;i++){ float an=float(i)*.7854+.2; glow+=max(texture(uQ,uv+vec2(cos(an),sin(an))*uGlowR*(i<4?.5:1.)/uRes).rgb-th,0.); }
  col+=glow/8.*(uMode<.5?.18:.8);
  col*=uExpo;
  if(uDirt>.5){ vec2 dm=texture(uDirtTex,uv).rg;
    float lmD=dot(col,vec3(.33));
    col=mix(col,vec3(lmD),dm.g*.18)+dm.g*(uMode<.5?.025:.012);      // дымка: меньше цвета и контраста, лёгкая вуаль
    col= uMode<.5 ? col*(1.-dm.r) : col+dm.r*vec3(.05,.06,.08); }

  vec2 s=(gl_FragCoord.xy-.5*uRes)/uRes.y; float rr=length(s);
  // неравномерная засветка конденсора: горячее пятно и слабое кольцо
  col*=1.+.08*uVig*(1.-smoothstep(0.,.55,length(s-vec2(-.06,.04))));
  col*=1.-.05*uVig*exp(-sq((rr-.40)/.07));
  // виньетка: мягкий спад и резкий край поля зрения окуляра
  col*=1.-uVig*.35*smoothstep(.15,.7,rr);
  float edge=.98-.26*uVig;
  float field=1.-smoothstep(edge-.022,edge+.006,rr);
  col=mix(vec3(.012,.011,.01),col,field);
  col=mix(col,col*vec3(1.03,1.,.92),uVig*smoothstep(edge-.25,edge,rr));
  if(uCamFx>.5){
    // слабый цветной шум матрицы — только в глубоких тенях (в тёмном поле)
    float lm=dot(col,vec3(.3,.5,.2));
    col+=(vec3(hash12(gl_FragCoord.xy+uTime*3.),hash12(gl_FragCoord.yx+uTime*4.),hash12(gl_FragCoord.xy*1.7+uTime*5.))-.5)*.025*(1.-smoothstep(0.,.12,lm));
  }
  float n=hash12(gl_FragCoord.xy+fract(uTime*7.)*vec2(113.,71.))-.5;
  col+=n*(uMode<.5?.006:.006+.008*dot(col,vec3(.3,.5,.2)));
  // Очень слабая неподвижная неоднородность матрицы поверх движущегося препарата.
  col+=(hash12(floor(gl_FragCoord.xy))-.5)*.0018;
  outColor=vec4(col,1.);
}