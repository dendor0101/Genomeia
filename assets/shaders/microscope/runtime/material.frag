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

uniform sampler2D uChip;
float ellipseMask(vec2 q,vec2 center,vec2 axes,float angle,float aa){
  vec2 d=rot(angle)*(q-center);float s=min(axes.x,axes.y);
  return 1.-smoothstep(-aa,aa,(length(d/axes)-1.)*s);
}
float lineMask(vec2 p,vec2 a,vec2 b,float width,float aa){
  vec2 d=b-a;float t=clamp(dot(p-a,d)/max(dot(d,d),.00001),0.,1.);
  float bend=(hash12(a*17.+b*31.)-.5)*.38;
  vec2 curve=vec2(-d.y,d.x)*sin(t*3.141593)*bend;
  return 1.-smoothstep(width-aa,width+aa,length(p-a-t*d-curve));
}
// x: оптическая неоднородность, y: поглощение. stain: спектральное поглощение.
vec2 cellFeatures(vec2 q,float kind,float seed,float time,float aa,float layer,out vec3 stain){
  stain=vec3(0.);vec2 material=vec2(0.);float rho=length(q);
  float central=exp(-pow(layer-2.,2.)*1.8),angle=hash12(vec2(seed,17.))*6.283;
  vec2 center=(hash22(vec2(seed,23.))-.5)*.22;
  float nucleus=0.,granules=0.,net=0.;
  if(kind<10.5){
    // 24 хлоропласта на срез: три живых скопления, эллипсоидная форма.
    for(int i=0;i<24;i++){
      float fi=float(i),cluster=floor(fi/8.),h=hash12(vec2(seed+layer,fi));
      float a=cluster*2.094+seed+time*(.65+.14*cluster)+.3*sin(time*.9+cluster);
      vec2 c=vec2(cos(a),sin(a))*(.35+.12*sin(time*.8+cluster));
      vec2 jitter=(hash22(vec2(seed+layer*3.,fi+8.))-.5)*.48;
      c+=rot(time*.9+cluster)*jitter;
      c+=.045*vec2(sin(time*2.3+fi),cos(time*1.9+fi));
      float r=.033+.025*h;
      float g=ellipseMask(q,c,vec2(r*1.65,r),a+fi+time*.7,aa);
      granules+=g;stain+=g*vec3(.43,.07,.68);
    }
    nucleus=ellipseMask(q,center,vec2(.16,.13),angle,aa)*central;
    material+=vec2(.085,.013)*granules+vec2(.12,.03)*nucleus;
  }else if(kind<11.5){
    float membrane=exp(-pow((rho-(.94+.025*sin(atan(q.y,q.x)*5.+seed)))/max(.016,aa),2.));
    float has=step(.28,hash12(vec2(seed,61.)));
    vec2 c=vec2(cos(angle),sin(angle))*(.89+.016*sin(time*.7+seed));
    nucleus=ellipseMask(q,c,vec2(.075,.14),-angle,aa)*has*central;
    float body=1.-smoothstep(.87,.98,rho);
    material+=vec2(.027,.006)*body+vec2(.10,.055)*membrane+vec2(.40,.32)*nucleus;
    stain+=body*vec3(.008,.018,.14)+membrane*vec3(.025,.10,.065)+nucleus*vec3(.60);
  }else if(kind<12.5){
    // Остеон: канал, волнистые ламеллы и лакуны. Время намеренно не используется.
    vec2 d=rot(angle)*(q-center);d*=vec2(.93,1.07);
    float r=length(d),a=atan(d.y,d.x),rr=r+.012*sin(a*5.+seed)+.008*sin(a*9.-seed);
    float visibility=1.-smoothstep(.02,.07,aa);
    float rings=(.5+.5*cos(rr*48.+seed))*.5;
    rings=mix(.25,rings,visibility);
    float canal=1.-smoothstep(.10-aa,.10+aa,r);
    material+=vec2(.032,.009)*rings+vec2(.24,.12)*canal;
    stain+=vec3(.012,.022,.047)*(rings+.3)+vec3(.20)*canal;
    for(int i=0;i<24;i++){
      float fi=float(i),h=hash12(vec2(seed,fi+40.)),a2=fi*2.399+seed;
      float r2=.23+.70*sqrt((fi+.5)/24.);
      vec2 c=vec2(cos(a2),sin(a2))*r2+center;
      float g=ellipseMask(q,c,vec2(.025+.012*h,.055),-a2,aa);
      material+=vec2(.055,.038)*g;stain+=g*vec3(.10,.085,.08);
    }
  }else if(abs(kind-15.)<.1){
    // Мышца в поперечном сечении. Случайные центры и размеры исключают регулярные полосы.
    vec2 x=rot(angle)*q*(3.5+hash12(vec2(seed,9.)))+seed*9.;
    vec2 base=floor(x);float f1=20.,f2=20.,id=0.;
    for(int y=-1;y<=1;y++)for(int x2=-1;x2<=1;x2++){
      vec2 cell=base+vec2(float(x2),float(y));vec2 c=cell+.12+.76*hash22(cell);
      float d=length(x-c);if(d<f1){f2=f1;f1=d;id=hash12(cell);}else f2=min(f2,d);
    }
    float fill=smoothstep(.055,.13+aa*4.,f2-f1)*(1.-smoothstep(.86,1.15,rho));
    material+=vec2(.045,.005)*fill;stain+=fill*vec3(.008,.09,.060);
    for(int i=0;i<9;i++){
      float fi=float(i),h=hash12(vec2(seed,fi+5.));
      vec2 c=(hash22(vec2(seed,fi+19.))-.5)*1.65+.025*vec2(sin(time*.8+fi),cos(time*.7+fi));
      float g=ellipseMask(q,c,vec2(.032,.061),fi+angle,aa);
      granules+=g;stain+=g*vec3(.18,.62,.10);
    }
    material+=vec2(.085,.012)*granules;
  }else if(abs(kind-14.)<.1||abs(kind-24.)<.1||abs(kind-26.)<.1||abs(kind-27.)<.1||abs(kind-36.)<.1){
    bool synapse=abs(kind-26.)<.1,eye=abs(kind-24.)<.1,touch=abs(kind-27.)<.1,chip=kind>35.5;
    vec3 dye=eye?vec3(.45,.24,.035):vec3(.09,.36,.11);
    for(int j=0;j<2;j++){
      if(j==1&&!synapse)break;
      vec2 soma=center+(synapse?vec2((float(j)-.5)*.34,0.):vec2(0.));
      vec2 axes=touch?vec2(.10,.12):synapse?vec2(.20,.27):eye?vec2(.20,.31):vec2(.25,.20);
      if(!chip){
        vec2 nq=q-soma;float theta=atan(nq.y,nq.x);
        float irregular=1.+(abs(kind-14.)<.1?.16:.05)*sin(theta*5.+seed)+.035*sin(theta*9.-seed);
        nucleus+=ellipseMask(soma+nq/irregular,soma,axes,angle,aa);
      }
      for(int i=0;i<7;i++){
        float fi=float(i)+float(j)*13.,a=fi*.897+angle+(hash12(vec2(seed,fi))-.5)*.40;
        vec2 end=vec2(cos(a),sin(a))*(.89+.16*hash12(vec2(seed,fi+50.)));
        vec2 bend=mix(soma,end,.48)+vec2(-sin(a),cos(a))*(.12*sin(fi+seed+time*.12));
        net+=lineMask(q,soma,bend,.012+.008*hash12(vec2(seed,fi+8.)),aa);
        net+=lineMask(q,bend,end,.009,aa);
        vec2 fork=rot(.32*(mod(fi,2.)*2.-1.))*(end-bend)*.8+bend;
        net+=lineMask(q,bend,fork,.006,aa);
      }
    }
    net=min(net,1.5)*central;nucleus*=central;
    stain+=dye*(net*.48+nucleus*.72);material+=vec2(.055,.010)*net+vec2(.13,.035)*nucleus;
    if(abs(kind-14.)<.1){
      for(int i=0;i<18;i++){
        float fi=float(i);vec2 c=(hash22(vec2(seed,fi+79.))-.5)*1.75;
        c+=.025*vec2(sin(time*.6+fi),cos(time*.8+fi));
        float g=ellipseMask(q,c,vec2(.022,.038),fi,aa);stain+=vec3(.22,.65,.08)*g;material+=vec2(.06,.025)*g;
      }
    }
    if(chip){
      vec2 uv=rot(angle*.18)*q/1.45+vec2(.5,.39);uv.y=1.-uv.y;
      vec4 chipPixel=texture(uChip,uv);float cover=step(0.,uv.x)*step(uv.x,1.)*step(0.,uv.y)*step(uv.y,1.);
      float alpha=chipPixel.a*cover*central;
      material+=vec2(.18,.07)*alpha;stain+=(vec3(1.)-chipPixel.rgb)*alpha*.85;
    }
  }else if(abs(kind-18.)<.1){
    float pulse=1.+.035*sin(time*1.4+seed);
    vec2 nq=q-center;float theta=atan(nq.y,nq.x);
    nq*=1.+.035*sin(theta*7.+seed)+.022*sin(theta*13.-seed);
    nucleus=ellipseMask(center+nq,center,vec2(.43,.39)*pulse,angle,max(aa,.025))*central;
    nucleus*=.65+.35*exp(-dot(nq-vec2(-.10,.12),nq-vec2(-.10,.12))/.16);
    float shell=exp(-pow((rho-.83)/.10,2.));
    float frost=dot(microtex(q*.12+seed),vec3(.3,.5,.2));
    material+=vec2(.10,.008)*shell*(.3+frost)+vec2(.10,.018)*nucleus;
    stain+=vec3(.015,.14,.17)*nucleus+vec3(.010,.019,.012)*shell;
  }else if(abs(kind-19.)<.1){
    // Локальная +X — именно переданный из генома угол выброса, как у Tail.
    vec2 c=vec2(.86,.025*sin(time*.8+seed));
    nucleus=ellipseMask(q,c,vec2(.13,.19),0.,aa)*central;
    material+=vec2(.22,.10)*nucleus;stain+=vec3(.15,.45,.34)*nucleus;
    for(int i=0;i<3;i++){
      float t=fract(time*.30+float(i)/3.+seed);vec2 d=vec2(.86+t*.49,.035*sin(t*8.+seed));
      float g=ellipseMask(q,d,vec2(.025),0.,aa)*(1.-t)*central;
      material+=vec2(.09,.02)*g;stain+=vec3(.04,.11,.30)*g;
    }
  }else if(abs(kind-20.)<.1){
    float contraction=1.+.09*sin(time*1.8+seed);float r=length(q-center)*contraction;
    float count=5.+floor(hash12(vec2(seed,15.))*4.);
    for(int i=0;i<8;i++){
      if(float(i)>=count)break;
      float radius=.07+float(i)*.095;
      float ring=exp(-pow((r-radius)/max(.014,aa),2.));
      material+=vec2(.065,.012)*ring;stain+=vec3(.06,.016,.022)*ring;
    }
  }else if(abs(kind-23.)<.1||abs(kind-32.)<.1){
    for(int i=0;i<28;i++){
      float fi=float(i),h=hash12(vec2(seed,fi));
      vec2 c=(hash22(vec2(seed,fi+29.))-.5)*1.65+.04*vec2(sin(time*.8+fi),cos(time*.6+fi));
      float g=ellipseMask(q,c,vec2(.032,.055+.025*h),fi+time*.15,aa);
      vec3 rgb=.12+.88*(.5+.5*cos(vec3(0.,2.094,4.189)+fi*2.4+(kind<24.?time*1.1:0.)));
      stain+=(vec3(1.)-rgb)*g*.5;material+=vec2(.07,.007)*g;
    }
  }else if(abs(kind-28.)<.1||abs(kind-29.)<.1){
    if(kind<28.5)nucleus=ellipseMask(q,center,vec2(.43,.40),angle,aa);
    else{
      vec2 d=rot(angle)*(q-center);float pulse=.02*sin(time*.9+seed);
      nucleus=1.-(1.-ellipseMask(d,vec2(-.20-pulse,0.),vec2(.30,.36),0.,aa))*(1.-ellipseMask(d,vec2(.20+pulse,0.),vec2(.30,.36),0.,aa));
    }
    nucleus*=central;stain+=vec3(.13,.38,.25)*nucleus;material+=vec2(.20,.07)*nucleus;
  }else{
    nucleus=ellipseMask(q,center,vec2(.17,.14),angle,aa)*central;
    material+=vec2(.08,.015)*nucleus;
    for(int i=0;i<8;i++){
      float fi=float(i);vec2 c=(hash22(vec2(seed+layer,fi+49.))-.5)*1.45;
      c+=.04*vec2(sin(time*.8+fi),cos(time*.65+fi));
      float g=ellipseMask(q,c,vec2(.03,.05),fi,aa);
      material+=vec2(.055,.012)*g;
      if(abs(kind-34.)<.1)stain+=g*vec3(.02,.32,.39);
    }
  }
  return material;
}

uniform float uTime,uLayer,uScale,uDensity,uDetailContrast;
in vec2 vLocal;in vec4 vCell;in vec3 vFiber;
void main(){
  float r2=dot(vLocal,vLocal);if(r2>=2.25)discard;
  float kind=vCell.x,bone=1.-step(.1,abs(kind-12.)),fat=1.-step(.1,abs(kind-11.));
  float muscle=1.-step(.1,abs(kind-15.));
  float time=uTime*(1.-bone),seed=vCell.y*17.31,rho=sqrt(r2);
  float support=1.-smoothstep(.80,1.45,rho),chord=sqrt(max(0.,1.-r2/2.25));
  vec2 flow=.035*vec2(sin(time*.83+seed+vLocal.y*2.),cos(time*.67+seed+vLocal.x*2.));
  float broad=dot(microtex((vLocal+flow)*.045+seed+uLayer*.137),vec3(.3,.5,.2));
  float rough=dot(microtex((vLocal+flow*.6)*.135+seed*.37+uLayer*.19),vec3(.3,.5,.2));
  float relief=pow(clamp((.78-rough)*2.4,0.,1.),1.2);
  float fraction=(uLayer<.5||uLayer>3.5)?.14:abs(uLayer-2.)<.1?.30:.21;
  float opd=(.055+.04*broad+relief*.05*(1.-muscle*.8))*(1.-fat*.85)*chord*fraction;
  float absorb=(.009+relief*.022)*(1.-fat*.95)*(1.-muscle*.6)*chord*fraction;
  if(bone>.5){opd=(.17+.14*broad+(relief-.35)*.09)*chord*fraction;absorb=(.045+.055*broad+.008*relief)*chord*fraction;}
  if(uLayer>.5&&uLayer<3.5){
    vec3 stain;float aa=max(.003,uScale/max(vCell.z,.001)*.7);
    vec2 f=cellFeatures(vLocal,kind,seed,time,aa,uLayer,stain);
    opd+=f.x*uDetailContrast;absorb+=f.y*uDetailContrast;
  }
  float bulk=mix(.86,1.18,clamp(vCell.w/.055,0.,1.));
  opd=max(opd,0.)*support*sqrt(uDensity)*bulk;absorb=max(absorb,0.)*support*uDensity*bulk;
  float depth=(.0015*sin(seed+uLayer+time*.45)+.004*(rough-.5))*support;
  outColor=vec4(opd,absorb,0.,opd*depth);
}