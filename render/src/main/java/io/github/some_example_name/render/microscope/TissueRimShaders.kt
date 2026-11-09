package io.github.some_example_name.render.microscope

/** Общая GPU-деформация каймы для поля, геометрии и всех пяти слоёв. */
internal object TissueRimShaders {
    const val vertex="""
        uniform highp sampler2D uRimNodes;
        uniform int uCoreCount;uniform float uRimRadius;
        uniform float uSmoothContour;
        float liveRadius(int i){int rows=textureSize(uLiveData,0).y/6;return texelFetch(uLiveData,ivec2(i%256,i/256+rows),0).z;}
        vec2 outward(vec2 edge,vec2 inside){
            float side=edge.x*inside.y-edge.y*inside.x<0.?-1.:1.;
            return vec2(edge.y,-edge.x)*side/max(length(edge),1e-6);
        }
        vec2 branchPoint(vec4 e,float side){
            // Узел соединения закреплён на живой клетке. Сглаживаем соседнюю
            // кайму к нему, а не вычисляем рекурсивно сглаживание всех соседей.
            vec2 a=live(uBodyOffset+int(e.x)),b=live(uBodyOffset+int(e.y))-a;
            vec2 n=outward(b,live(uBodyOffset+int(e.z))-a),d=live(uBodyOffset+int(e.w))-a;
            vec2 m=vec2(-d.y,d.x)*side/max(length(d),1e-6);
            float r=liveRadius(uBodyOffset+int(e.x)),c=dot(n,m);
            return a+(n+m)*(uRimRadius+r)*.5/max(.35,1.+c)+(n-m)*(uRimRadius-r)*.5/max(.35,1.-c);
        }
        vec2 relaxed(int id,vec2 a){
            if(uSmoothContour<=1.)return a;
            vec4 link=fetch(uRimNodes,id);
            if(link.z>.5)a+=(uSmoothContour-1.)*.25*((live(uBodyOffset+int(link.x))+live(uBodyOffset+int(link.y)))*.5-a);
            return a;
        }
        vec2 corner(int id,int toward){
            vec4 link=fetch(uRimNodes,id);vec2 a=live(uBodyOffset+id);
            if(link.z< -1.5){
                int at=uCoreCount+(int(link.x)-uCoreCount)*2;
                vec4 e=fetch(uRimNodes,at),f=fetch(uRimNodes,at+1);
                if(int(e.y)!=toward){at=uCoreCount+(int(link.y)-uCoreCount)*2;e=fetch(uRimNodes,at);f=fetch(uRimNodes,at+1);}
                return branchPoint(e,f.x);
            }
            if(link.z<.5)return a;
            int at=uCoreCount+(int(link.w)-uCoreCount)*2;
            vec4 e=fetch(uRimNodes,at),f=fetch(uRimNodes,at+1);
            vec2 b=live(uBodyOffset+int(e.y)),c=live(uBodyOffset+int(e.z));
            vec2 p=live(uBodyOffset+int(e.w)),q=live(uBodyOffset+int(f.x));
            vec2 n=outward(b-a,c-a),m=outward(p-a,q-a),sum=n+m;
            vec2 offset=dot(p-a,n)<=1e-6?
                (length(sum)>1e-5?normalize(sum):n):sum/max(.7,1.+dot(n,m));
            return a+offset*uRimRadius*f.z;
        }
        vec2 contourCurve(int ai,int bi,int pi,float t){
            vec2 a=corner(ai,ai),b=corner(bi,ai),p=corner(pi,ai);
            if(uSmoothContour>1.){
                // Те же положительные веса, что у запечённых UV. Не выдавливаем
                // нормаль сглаженной кривой: в ямках её эквидистанта образует петлю.
                vec4 bl=fetch(uRimNodes,bi),pl=fetch(uRimNodes,pi);
                float extra=(uSmoothContour-1.)*.25;
                vec2 ar=a+extra*((b+p)*.5-a);
                if(bl.z>.5)b+=extra*((a+corner(int(bl.x)==ai?int(bl.y):int(bl.x),bi))*.5-b);
                if(pl.z>.5)p+=extra*((a+corner(int(pl.x)==ai?int(pl.y):int(pl.x),pi))*.5-p);
                a=ar;
            }
            return mix(mix((p+a)*.5,a,t),mix(a,(a+b)*.5,t),t);
        }
        vec2 corePoint(int id){
                vec2 a=live(uBodyOffset+id);
                if(uSmoothContour>0.&&uParticleBatch==0){
                    vec4 link=fetch(uRimNodes,id);
                    if(link.z>.5){
                        vec2 b=relaxed(int(link.x),live(uBodyOffset+int(link.x)));
                        vec2 p=relaxed(int(link.y),live(uBodyOffset+int(link.y)));
                        a=mix(a,relaxed(id,a)*.75+(b+p)*.125,min(uSmoothContour,1.));
                    }
                }
                return a;
        }
        vec2 point(int id){
            if(id<uCoreCount)return corePoint(id);
            vec4 e=fetch(uRimNodes,uCoreCount+(id-uCoreCount)*2),f=fetch(uRimNodes,uCoreCount+(id-uCoreCount)*2+1);
            if(f.w< -1.5)return branchPoint(e,f.x);
            if(f.w<0.){
                vec2 a=corePoint(int(e.x));float r=liveRadius(uBodyOffset+int(e.x));
                float angle;
                if(f.z>.5)angle=f.x*6.28318530718;
                else{
                    vec2 u=corePoint(int(e.y))-a,v=corePoint(int(e.z))-a;
                    float ab=atan(u.y,u.x),ac=atan(v.y,v.x);
                    float gap=e.y==e.z?6.28318530718:mod(ac-ab+6.28318530718,6.28318530718);
                    vec2 n=vec2(-sin(ab),cos(ab)),m=vec2(sin(ac),-cos(ac));
                    if(gap<3.14159265359)return a+(n+m)/max(.65,1.+dot(n,m))*r;
                    angle=ab+1.57079632679+(gap-3.14159265359)*f.x;
                }
                return a+vec2(cos(angle),sin(angle))*r;
            }
            if(uSmoothContour>=1.&&f.w>.5)return contourCurve(int(e.x),int(e.y),int(e.w),.5+.5*f.y);
            vec2 a=live(uBodyOffset+int(e.x)),b=live(uBodyOffset+int(e.y));
            vec2 c=live(uBodyOffset+int(e.z)),p=live(uBodyOffset+int(e.w)),q=live(uBodyOffset+int(f.x));
            vec2 n=outward(b-a,c-a),m=outward(p-a,q-a),sum=n+m;
            vec2 offset;
            if(dot(p-a,n)<=1e-6){
                vec2 join=length(sum)>1e-5?normalize(sum):n;
                offset=mix(join,n,f.y);offset/=max(length(offset),1e-6);
            }else{
                // Вогнутый угол: ограниченный стык, без нахлёста двух круговых вееров.
                offset=sum/max(.7,1.+dot(n,m));
            }
            vec2 result=a+offset*uRimRadius*f.z;
            if(uSmoothContour>0.&&f.w>.5){
                // Сглаживаем уже построенный внешний контур. Его геометрия и UV
                // проходят одинаковую интерполяцию, включая общую середину ребра.
                vec2 curve=contourCurve(int(e.x),int(e.y),int(e.w),.5+.5*f.y);
                result=mix(result,curve,min(uSmoothContour,1.));
            }
            return result;
        }
    """
}
