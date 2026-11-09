package io.github.some_example_name.render.microscope

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL30.*
import com.badlogic.gdx.utils.Disposable

/** ES3 raster-prefix compaction, no readback or CPU cell traversal.
 * 128² tiles, 16-pixel vertical guards: horizontal Gaussian reads the original image;
 * vertical Gaussian reads the compact atlas and scatters into the existing PSF target.
 * Conservative triangle AABBs + one-tile dilation include the rim and the filter support.
 * Prefix mapping is shared by all layers of the same resolution for this frame only.
 * Dense coverage bypasses padded tile quads on GPU. Gaussian sample budget is unchanged.
 */
internal class OpticalPackedPsf:Disposable {
    private val full=GpuOpticalDraw()
    private var mask:OpticalTarget?=null
    private var scans=emptyList<OpticalTarget>()
    private var atlas:OpticalTarget?=null
    private var w=0;private var h=0;private var nx=0;private var ny=0
    private val header="#version 300 es\nprecision highp float;precision highp int;\n"
    private val vertex=Gdx.files.internal("shaders/microscope/vs_gpu_full.vert").readString()
    private var maskReady=false
    private var prefixIndex=0
    fun beginFrame(){maskReady=false}
    private val occupancy=OpticalProgram(TissueIslandShaders.meshVertex.substringBefore("void main(){")+"""
        uniform vec2 uImageSize,uTileGrid;
        void main(){
            ivec3 ids=ivec3(fetch(uTriangles,gl_InstanceID).xyz);
            vec2 a=(point(ids.x)/uSpan+.5)*uImageSize/128.,b=(point(ids.y)/uSpan+.5)*uImageSize/128.,c=(point(ids.z)/uSpan+.5)*uImageSize/128.;
            vec2 lo=floor(min(a,min(b,c))),hi=floor(max(a,max(b,c)))+1.;
            int k=gl_VertexID;vec2 corner=vec2((k==1||k==2||k==4)?1.:0.,(k==2||k==4||k==5)?1.:0.);
            gl_Position=vec4(mix(lo,hi,corner)/uTileGrid*2.-1.,0.,1.);
        }
    """,header+"out vec4 outColor;void main(){outColor=vec4(1.);}",true)
    private val scan=OpticalProgram(vertex,header+"""
        uniform sampler2D uInput;uniform int uOffset,uInit;uniform vec2 uEyeRadius,uImageSize;out vec4 outColor;
        void main(){ivec2 p=ivec2(gl_FragCoord.xy),sz=textureSize(uInput,0);float v=0.;
            if(uInit==1){
                if(uEyeRadius.x>0.){
                    vec2 closest=max(abs((vec2(p)+.5)*128.-uImageSize*.5)-64.,0.)/(uImageSize*uEyeRadius);
                    if(dot(closest,closest)>1.){outColor=vec4(0.);return;}
                }
                for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){ivec2 q=p+ivec2(x,y);if(all(greaterThanEqual(q,ivec2(0)))&&all(lessThan(q,sz)))v=max(v,texelFetch(uInput,q,0).r);}}
            else {v=texelFetch(uInput,p,0).r;int id=p.y*sz.x+p.x-uOffset;if(id>=0)v+=texelFetch(uInput,ivec2(id%sz.x,id/sz.x),0).r;}
            outColor=vec4(v);
        }
    """,true)
    private val tileVertex=header+"""
        uniform sampler2D uPrefix;uniform vec2 uImageSize,uAtlasSize,uEyeRadius;uniform int uPass;
        flat out ivec2 vTile,vSlot;flat out int vDense;
        float at(int i,ivec2 sz){return i<0?0.:texelFetch(uPrefix,ivec2(i%sz.x,i/sz.x),0).r;}
        void main(){ivec2 sz=textureSize(uPrefix,0);int id=gl_InstanceID;float rank=at(id,sz),prev=at(id-1,sz);
            vDense=at(sz.x*sz.y-1,sz)>.60*float(sz.x*sz.y)?1:0;
            if(vDense==1){
                if(id!=0){gl_Position=vec4(2.,2.,0.,1.);return;}
                vTile=ivec2(0);vSlot=ivec2(0);int k=gl_VertexID;
                vec2 corner=vec2((k==1||k==2||k==4)?1.:0.,(k==2||k==4||k==5)?1.:0.);
                if(uEyeRadius.x>0.)corner=clamp(.5+(corner*2.-1.)*uEyeRadius,0.,1.);
                gl_Position=vec4(corner*uImageSize/(uPass==0?uAtlasSize:uImageSize)*2.-1.,0.,1.);return;
            }
            if(rank==prev){gl_Position=vec4(2.,2.,0.,1.);return;}
            int slot=int(rank)-1;vTile=ivec2(id%sz.x,id/sz.x);vSlot=ivec2(slot%sz.x,slot/sz.x);
            int k=gl_VertexID;vec2 corner=vec2((k==1||k==2||k==4)?1.:0.,(k==2||k==4||k==5)?1.:0.);
            vec2 p=uPass==0?(vec2(vSlot)+corner)*vec2(128.,160.):(vec2(vTile)+corner)*128.;
            vec2 size=uPass==0?uAtlasSize:uImageSize;gl_Position=vec4(p/size*2.-1.,0.,1.);
        }
    """
    private val blur=OpticalProgram(tileVertex,header+"""
        uniform sampler2D uMap;uniform vec2 uImageSize,uAtlasSize,uEyeRadius;uniform int uPass;
        uniform float uAperture,uPixelRatio,uTissueBlur;
        flat in ivec2 vTile,vSlot;flat in int vDense;out vec4 outColor;
        vec4 sampleMap(vec2 uv){
            if(vDense==1&&uPass==1)uv=clamp(uv,vec2(.5)/uAtlasSize,(uImageSize-.5)/uAtlasSize);
            return texture(uMap,uv);
        }
        void main(){
            vec2 coord,size,dir;
            if(uPass==0){coord=gl_FragCoord.xy-vec2(vSlot)*vec2(128.,160.)+vec2(vTile)*128.-vec2(0.,16.);size=uImageSize;dir=vec2(1,0);}
            else {coord=gl_FragCoord.xy-vec2(vTile)*128.+vec2(vSlot)*vec2(128.,160.)+vec2(0.,16.);size=uAtlasSize;dir=vec2(0,1);}
            if(vDense==1)coord=gl_FragCoord.xy;
            vec2 uv=coord/size;vec4 c=sampleMap(uv);
            float sigma=max(.35,clamp(.55+abs(c.a)*uAperture,.55,7.)*uPixelRatio);
            sigma=sqrt(sigma*sigma+pow(uTissueBlur*uPixelRatio,2.));
            vec3 sum=c.rgb;float weight=1.,inv=.5/(sigma*sigma);
            for(int i=0;i<8;i++){
                float d=1.+float(i)*2.,a=exp(-d*d*inv),b=exp(-(d+1.)*(d+1.)*inv),ww=a+b;
                if(ww<.00005)break;
                vec2 stepUV=dir*(d+b/ww)/size;
                sum+=(sampleMap(uv+stepUV).rgb+sampleMap(uv-stepUV).rgb)*ww;weight+=2.*ww;
            }
            outColor=vec4(sum/weight,c.a);
        }
    """,true)
    private fun configure(width:Int,height:Int){
        if(w==width&&h==height)return
        mask?.dispose();for(t in scans)t.dispose();atlas?.dispose()
        w=width;h=height;nx=(w+127)/128;ny=(h+127)/128
        mask=OpticalTarget(nx,ny)
        scans=List(2){OpticalTarget(nx,ny,true,internalFormat=GL_RGBA32F)}
        atlas=OpticalTarget(nx*128,ny*160,true)
    }
    private fun state(){val g=Gdx.gl;g.glDisable(GL_SCISSOR_TEST);g.glDisable(GL_DEPTH_TEST);g.glDisable(GL_BLEND);g.glColorMask(true,true,true,true)}
    fun render(input:Int,width:Int,height:Int,aperture:Float,ratio:Float,tissueBlur:Float,eyepiece:EyepieceFootprint,bindResult:()->Unit,drawOccupancy:(OpticalProgram)->Unit){
        configure(width,height);state()
        if(!maskReady){

        mask!!.bind();Gdx.gl.glClearColor(0f,0f,0f,0f);Gdx.gl.glClear(GL_COLOR_BUFFER_BIT)
        occupancy.use();occupancy.v2("uImageSize",w.toFloat(),h.toFloat());occupancy.v2("uTileGrid",nx.toFloat(),ny.toFloat());drawOccupancy(occupancy)

        scans[0].bind();scan.use();eyepiece.bindRadius(scan);scan.v2("uImageSize",w.toFloat(),h.toFloat());scan.texture("uInput",mask!!.texture,0);scan.i("uInit",1);scan.i("uOffset",0);full.full()
        var current=0;var offset=1
        while(offset<nx*ny){val next=1-current;scans[next].bind();scan.use();scan.texture("uInput",scans[current].texture,0);scan.i("uInit",0);scan.i("uOffset",offset);full.full();current=next;offset*=2}
        prefixIndex=current;maskReady=true;
        }
        // Restore the generated-vertex VAO also when the prefix was reused.
        full.bind()
        blur.use();eyepiece.bindRadius(blur);blur.texture("uPrefix",scans[prefixIndex].texture,1);blur.v2("uImageSize",w.toFloat(),h.toFloat());blur.v2("uAtlasSize",atlas!!.width.toFloat(),atlas!!.height.toFloat());blur.f("uAperture",aperture);blur.f("uPixelRatio",ratio);blur.f("uTissueBlur",tissueBlur)
        atlas!!.bind();blur.i("uPass",0);blur.texture("uMap",input,0)
        // full.full() left the dedicated VAO bound; vertices are generated from gl_VertexID.
        Gdx.gl30.glDrawArraysInstanced(GL_TRIANGLES,0,6,nx*ny);
        bindResult();Gdx.gl.glViewport(0,0,w,h);Gdx.gl.glClearColor(0f,0f,0f,0f);Gdx.gl.glClear(GL_COLOR_BUFFER_BIT);blur.i("uPass",1);blur.texture("uMap",atlas!!.texture,0)
        Gdx.gl30.glDrawArraysInstanced(GL_TRIANGLES,0,6,nx*ny);
    }
    override fun dispose(){mask?.dispose();for(t in scans)t.dispose();atlas?.dispose();scan.dispose();blur.dispose();occupancy.dispose();full.dispose()}
}
