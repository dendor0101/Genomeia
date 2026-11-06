#version 300 es
precision highp float;
precision highp int;
uniform int uRows,uActiveRows;
const vec2 QUAD[6]=vec2[6](vec2(0,0),vec2(1,0),vec2(1,1),vec2(0,0),vec2(1,1),vec2(0,1));
void main(){
    vec2 p=QUAD[gl_VertexID];
    // Шесть полос одним draw: резерв таблицы не становится работой каждого кадра.
    float y=(float(gl_InstanceID*uRows)+p.y*float(uActiveRows))/float(uRows*6);
    gl_Position=vec4(p.x*2.-1.,y*2.-1.,0.,1.);
}
