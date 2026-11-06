package io.github.some_example_name.render.microscope

/** Пять запечённых периодических карт на каждый присутствующий тип ткани. Между треугольниками общие UV вершин;
 * дорогой cellFeatures вызывается только при создании плитки, а не при отрисовке тела. */
internal object TissueIslandShaders {
    private const val header="#version 300 es\nprecision highp float;precision highp int;\n"
    val meshVertex=header+"""
        uniform highp sampler2D uRest,uTriangles,uLiveData;
        uniform int uBodyOffset,uParticleBatch,uSmoothUvCount;uniform vec2 uCam,uSpan;uniform float uMaterialRadius;
        out vec2 vRestPoint,vWorldPoint;out vec2 vRelief;
        out vec3 vBary;flat out vec3 vEdgeLift;flat out float vCenterLift;flat out ivec3 vKinds;
        vec4 fetch(highp sampler2D t,int i){int w=textureSize(t,0).x;return texelFetch(t,ivec2(i%w,i/w),0);}
        vec2 live(int i){int rows=textureSize(uLiveData,0).y/6;
            return texelFetch(uLiveData,ivec2(i%256,i/256+rows),0).xy-uCam;}
    """+TissueRimShaders.vertex+"""
        int kind(int i){return clamp(int(texelFetch(uLiveData,ivec2(i%256,i/256),0).x+.5)-10,0,27);}
        void main(){
            vec4 tri=fetch(uTriangles,gl_InstanceID);ivec3 ids=ivec3(tri.xyz);int boundary=int(tri.w);
            int k=gl_VertexID;vec2 p=point(ids[k]);
            vec4 ra=fetch(uRest,ids.x),rb=fetch(uRest,ids.y),rc=fetch(uRest,ids.z);
            // У виртуальной вершины свободная компонента rest.w хранит владельца.
            // Три лишние выборки описаний каймы в каждом слое больше не нужны.
            vKinds=ivec3(kind(uBodyOffset+(ids.x<uCoreCount?ids.x:int(ra.w))),
                kind(uBodyOffset+(ids.y<uCoreCount?ids.y:int(rb.w))),kind(uBodyOffset+(ids.z<uCoreCount?ids.z:int(rc.w))));
            vec4 r=k==0?ra:k==1?rb:rc;vRestPoint=r.xy;vRelief=vec2(abs(r.z),ids[k]<uCoreCount?(r.w<0.?liveRadius(uBodyOffset+ids[k]):r.w+uRimRadius):(r.z<0.?uRimRadius:0.));
            if(uSmoothContour>0.&&uSmoothUvCount>0){
                vec4 uv=fetch(uRest,uSmoothUvCount+ids[k]);
                vRestPoint=mix(vRestPoint,mix(uv.xy,uv.zw,max(uSmoothContour-1.,0.)),min(uSmoothContour,1.));
            }
            if(uParticleBatch!=0){int i=uBodyOffset+(ids[k]<uCoreCount?ids[k]:int(r.w));float seed=texelFetch(uLiveData,ivec2(i%256,i/256),0).z;vRestPoint+=vec2(seed*123.,seed*137.);}
            vec3 d=vec3(ra.w,rb.w,rc.w);
            vEdgeLift=vec3(0.);
            for(int e=0;e<3;e++){
                // Одинаковая середина общего ребра для ОБОИХ треугольников.
                // Внутреннее ребро между двумя краевыми клетками не является контуром.
                if(boundary>=0&&(boundary&(1<<e))==0&&max(d[(e+1)%3],d[(e+2)%3])<1e-6)
                    vEdgeLift[e]=uMaterialRadius*.5;
            }
            vCenterLift=boundary==7?uMaterialRadius*.5:0.;
            vWorldPoint=p+uCam;vBary=vec3(0.);vBary[k]=1.;
            gl_Position=vec4(p/uSpan*2.,0.,1.);
        }
    """
    private const val surface="""
        in vec2 vRestPoint,vWorldPoint,vRelief;in vec3 vBary;
        flat in vec3 vEdgeLift;flat in float vCenterLift;
        float edgeDistance(){
            // Непрерывное поле: значения и поправка на общем ребре совпадают.
            // Прежний min расстояния до собственного внешнего ребра давал разрыв.
            vec3 edge=vec3(vBary.y*vBary.z,vBary.z*vBary.x,vBary.x*vBary.y);
            return max(0.,vRelief.y+4.*dot(edge,vEdgeLift)+27.*vBary.x*vBary.y*vBary.z*vCenterLift);
        }
        float height(){return max(vRelief.x,.018)*smoothstep(0.,.085,edgeDistance());}
    """
    val fieldFragment=header+surface+"""
        layout(location=0) out vec4 outField;layout(location=1) out vec4 outTint;
        vec2 pack16(float x){float v=floor(clamp(x,0.,1.)*65535.+.5);return vec2(floor(v/256.),mod(v,256.))/255.;}
        void main(){outField=vec4(pack16(.5-edgeDistance()/1.5),pack16(height()/.08));outTint=vec4(1.);}
    """
    val geometryFragment=header+surface+"""
        layout(location=0) out vec4 outColor;
        void main(){outColor=vec4(-edgeDistance(),1.,vRestPoint);}
    """
    val templateVertex=header+"""
        out vec2 vUv;
        void main(){vUv=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(vUv*2.-1.,0.,1.);}
    """
    fun templateFragment(original:String):String {
        val prefix=original.substring(0,original.indexOf("uniform float uTime,uLayer,uScale,uDensity,uDetailContrast;"))
        return prefix+"""
            in vec2 vUv;uniform float uLayer,uKind,uTileSize;layout(location=1) out vec4 outDye;
            uniform int uBakeCount;uniform float uBakeFirst;
            void bakeSlice(vec2 vUv,float uLayer,out vec4 result,out vec4 pigment){
                vec2 p=vUv*5.6;float rough=dot(microtex(vUv*4.+uLayer*.17),vec3(.3,.5,.2));
                float broad=dot(microtex(vUv+uLayer*.23),vec3(.3,.5,.2));
                float relief=pow(clamp((.78-rough)*2.4,0.,1.),1.2);
                float fraction=(uLayer<.5||uLayer>3.5)?.14:abs(uLayer-2.)<.1?.30:.21;
                vec2 material=vec2((.055+.04*broad+relief*.05)*fraction,(.009+relief*.022)*fraction);
                float bone=1.-step(.1,abs(uKind-12.)),fat=1.-step(.1,abs(uKind-11.));
                float muscle=1.-step(.1,abs(uKind-15.));
                material=vec2((.055+.04*broad+relief*.05*(1.-muscle*.8))*(1.-fat*.85),
                    (.009+relief*.022)*(1.-fat*.95)*(1.-muscle*.6))*fraction;
                if(bone>.5)material=vec2(.17+.14*broad+(relief-.35)*.09,.045+.055*broad+.008*relief)*fraction;
                vec3 dye=vec3(0.);float total=0.;vec2 features=vec2(0.);
                if(uLayer>.5&&uLayer<3.5){
                    vec2 cell=floor(p/1.4-.5);
                    for(int y=0;y<2;y++)for(int x=0;x<2;x++){
                        vec2 id=cell+vec2(x,y),wrapped=mod(id,4.);
                        float seed=17.31+hash12(wrapped)*5.;
                        vec2 center=(id+.5+(hash22(wrapped)-.5)*.3)*1.4;
                        vec2 q=rot(hash12(wrapped+31.)*6.283)*(p-center);
                        float w=1.-smoothstep(.8,1.35,length(q));
                        vec3 stain;vec2 f=cellFeatures(q,uKind,seed,0.,5.6/uTileSize*.7,uLayer,stain);
                        features+=f*w;dye+=stain*w;total+=w;
                    }
                    material+=features/max(total,.01);dye/=max(total,.01);
                }
                result=vec4(material,rough,1.);pigment=vec4(dye,1.);
            }
            void main(){
                if(uBakeCount==1){bakeSlice(vUv,uBakeFirst,outColor,outDye);return;}
                outColor=vec4(0.);outDye=vec4(0.);
                for(int j=0;j<3;j++){
                    if(j>=uBakeCount)break;
                    float source=uBakeFirst+float(j);
                    // Совмещаем исходные фазы при t=0. После запекания группа
                    // движется целиком: независимые сдвиги потребовали бы новых выборок.
                    vec2 shift=abs(uKind-12.)<.1?vec2(0.):
                        (vec2(sin(source),cos(source))-vec2(sin(uLayer),cos(uLayer)))*.018;
                    vec4 value,pigment;bakeSlice(vUv+shift,source,value,pigment);
                    value.b/=float(uBakeCount);outColor+=value;outDye+=pigment;
                }
            }
        """
    }
    fun material(original:String,resolve:String):String {
        val field=resolve.substring(resolve.indexOf("uniform sampler2D uTissueField;"),resolve.indexOf("uniform sampler2D uAccum,uA;"))
        return header+surface+field+"""
            uniform highp sampler2DArray uTemplates,uTemplateDye;
            uniform int uTileStride,uTileLayer;
            uniform ivec4 uTileSlots[7];flat in ivec3 vKinds;
            uniform vec2 uCam;
            uniform float uMaterialRadius,uTime,uLayer,uScale,uDensity,uDetailContrast,uFocus,uAperture,uPixelRatio,uTissueBlur;
            layout(location=0) out vec4 outColor;layout(location=1) out vec4 outDye;
            vec3 weights(){
                if(vKinds.x==vKinds.y&&vKinds.x==vKinds.z)return vec3(1.,0.,0.);
                vec3 w=max(vBary,vec3(0.));w/=max(dot(w,vec3(1.)),1e-6);
                // GPU линейно интерполирует веса клеток-вершин. Редкий тип
                // не исчезает из-за большинства, а на общем ребре третья
                // вершина имеет нулевой вес с обеих сторон.
                // Повторный тип выбираем из атласа один раз.
                if(vKinds.y==vKinds.x){w.x+=w.y;w.y=0.;}
                if(vKinds.z==vKinds.x){w.x+=w.z;w.z=0.;}
                else if(vKinds.z==vKinds.y){w.y+=w.z;w.z=0.;}
                return w;
            }
            void main(){
                vec2 rest=vRestPoint/5.6,dx=dFdx(rest),dy=dFdy(rest);
                float tileSize=float(textureSize(uTemplates,0).x);
                float depth=.5+.0018*(vWorldPoint.x-uCam.x)+.0012*(vWorldPoint.y-uCam.y)+reliefDepth(height(),uLayer);
                float sigma=max(.35,clamp(.55+abs(depth-uFocus)*uAperture,.55,7.)*uPixelRatio);
                sigma=sqrt(sigma*sigma+pow(uTissueBlur*uPixelRatio,2.))*uScale/max(uMaterialRadius,.001);
                float footprint=max(length(dx),length(dy))*tileSize;
                float lod=log2(max(1.,max(footprint,sigma*tileSize/5.6)));
                vec3 w=weights(),dye=vec3(0.);vec4 f=vec4(0.);
                for(int i=0;i<3;i++)if(w[i]>0.){
                    int kind=vKinds[i],slot=uTileSlots[kind/4][kind%4];
                    vec2 flow=(uLayer>.5&&uLayer<3.5&&kind!=2)?vec2(sin(uTime*.7+uLayer),cos(uTime*.51+uLayer))*.018:vec2(0.);
                    vec3 uv=vec3(rest+flow,float(slot*uTileStride+uTileLayer));
                    f+=textureGrad(uTemplates,uv,dx,dy)*w[i];
                    dye+=textureLod(uTemplateDye,uv,lod).rgb*w[i];
                }
                float bulk=mix(1.5,2.5,clamp(height()/.055,0.,1.));
                float contrast=uLayer>.5&&uLayer<3.5?uDetailContrast:1.;
                float opd=f.r*sqrt(uDensity)*bulk*contrast,absorb=f.g*uDensity*bulk*contrast;
                outColor=vec4(opd,absorb,0.,opd*.004*(f.b-.5));
                outDye=vec4(dye*sqrt(uDensity)*uDetailContrast*bulk/(1.+sigma*9.),0.);
            }
        """
    }
}
