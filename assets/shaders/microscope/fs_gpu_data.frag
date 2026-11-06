#version 300 es
precision highp float;
precision highp int;
uniform highp usampler2D uRaw,uMapping;
uniform float uLag;
uniform vec2 uRadiusRange;
uniform int uCount,uRows;
uniform ivec4 uMaterials[64];
layout(location=0) out vec4 outData;
uint raw(int id,int word){return texelFetch(uRaw,ivec2((id%128)*10+word,id/128),0).r;}
vec2 position(int id){return mix(vec2(uintBitsToFloat(raw(id,0)),uintBitsToFloat(raw(id,1))),vec2(uintBitsToFloat(raw(id,7)),uintBitsToFloat(raw(id,8))),uLag)*.25;}
vec2 direction(uint v){return vec2(float(int(v<<16)>>16),float(int(v)>>16))/32767.;}
float angle(int id){vec2 d=mix(direction(raw(id,6)),direction(raw(id,9)),uLag);return atan(d.y,d.x);}
void main(){
    ivec2 p=ivec2(gl_FragCoord.xy);int row=p.y/uRows;int id=(p.y%uRows)*256+p.x;
    if(id>=uCount){outData=vec4(0.);return;}
    uvec4 m=texelFetch(uMapping,ivec2(p.x,p.y%uRows),0);
    int source=id,anchor=int(m.z),born=int(m.x);uint type=raw(source,4);
    float group=float(int(m.w));float r=(uRadiusRange.x+float((raw(source,3)>>16)&255u)/255.*uRadiusRange.y)*.25;
    bool cell=((type>>24)&1u)==0u;
    int kind=int(type&255u);
    if(row==0)outData=vec4(float(cell?uMaterials[kind/4][kind%4]:27)+10.,group,float((type>>8)&65535u)/65535.,.5);
    else if(row==1)outData=vec4(position(source),r,0.);
    // Два точных 16-битных слова: большой номер тика не теряет младшие биты во float.
    else if(row==2)outData=vec4(position(anchor),r,born<0?-1.:float(born>>16));
    else if(row==3)outData=vec4(float(m.y&255u),float((m.y>>8)&255u),float((m.y>>16)&255u),float(m.y>>24))/255.;
    else if(row==4)outData=vec4(angle(source),group,group,float(born&65535));
    else outData=vec4(position(anchor),angle(anchor),r);
}
