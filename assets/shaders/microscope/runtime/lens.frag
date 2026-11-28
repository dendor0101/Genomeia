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

uniform vec2 uRes; uniform vec4 uHair;

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

float dust(vec2 s, float K, float prob, float r0, float r1, float soft0, float soft1, float a0, float a1, float seed){
  vec2 g=s*K+seed, i0=floor(g), f0=fract(g); float acc=0.;
  for(int y=-1;y<=1;y++) for(int x=-1;x<=1;x++){
    vec2 gg=vec2(float(x),float(y)), id=i0+gg; if(hash12(id+seed)>prob) continue;
    vec2 q=(f0-gg-hash22(id+seed*1.3))/K;
    float sz=mix(r0,r1,sq(hash12(id+seed+1.))), d=length(q)/sz;
    float soft=mix(soft0,soft1,hash12(id+seed+2.));
    float disk=1.-smoothstep(1.-soft,1.,d);
    float ring=exp(-sq((d-1.+soft*.5)/(soft*.45+.03)))*step(.45,hash12(id+seed+3.));
    acc+=(disk*(.35+.25*hash12(id+seed+4.))+ring*.7)*mix(a0,a1,hash12(id+seed+5.));
  }
  return acc;
}
void main(){
  vec2 s=(gl_FragCoord.xy-.5*uRes)/uRes.y;
  float px=1./uRes.y;
  float dirt=dust(s,5.,.42,.018,.055,.25,.6,.05,.11,17.)          // крупные мягкие «бублики» пыли
            +dust(s,13.,.30,.006,.014,.3,.7,.05,.10,53.)           // средние
            +dust(s,46.,.06,max(.0016,px*1.2),.0032,.35,.6,.25,.5,91.);  // мелкие резкие точки на матрице
  // мягкое волокно вне фокуса
  vec2 hl=rot(uHair.z)*(s-uHair.xy); float hx=hl.x;
  float hy=hl.y-.35*hx*hx-.03*sin(hx*9.+1.3), hsl=.7*hx+.27*cos(hx*9.+1.3);
  float hd=abs(hy)/sqrt(1.+hsl*hsl);
  dirt+=(1.-smoothstep(.0015,.011,hd))*(1.-smoothstep(uHair.w*.7,uHair.w,abs(hx)))*.075;
  // отпечаток: пятно дымки с дугами папиллярных линий
  vec2 fc=s-vec2(-.38,-.22); float fm=exp(-dot(fc*vec2(1.,1.35),fc*vec2(1.,1.35))/.035)*smoothstep(.35,.7,fbm3(s*2.3+4.));
  float rr=length(fc*vec2(1.,1.3))+.012*vnoise(s*18.);
  float ridge=.5+.5*sin(rr*230.);
  float haze=fm*(.55+.45*ridge)+.25*smoothstep(.6,.9,fbm3(s*1.2+9.));
  outColor=vec4(clamp(dirt,0.,1.),clamp(haze,0.,1.),0.,1.);
}