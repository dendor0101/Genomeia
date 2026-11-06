package io.github.some_example_name.render.microscope

import kotlin.math.*

/** Параметры оптики и камеры одного игрового кадра. */
class OpticalState internal constructor(frame:OpticalPipeline,val settings:MicroscopeSettings) {
    var width=frame.width;var height=frame.height
    var camX=0.0;var camY=0.0;var scale=.01;var time=0.0;var focus=.5
    /** Средний радиус и покрытие видимой ткани из игрового снимка. */
    var liveRadius = 0.0
    var liveCoverage = -1.0
    internal val rect=IntArray(4)
    private var detail=1f
    private var flowX=0.0;private var flowY=0.0
    internal val parallax=MicroscopeParallax()
    private var exposure=1.0;private var hunt=0.0;private var coverage=0.0;private var frameCount=0
    private val whiteBalance=doubleArrayOf(1.0,1.0,1.0)
    private var aperture=1.07;private var phase=.52;private var aberration=.73
    private var lastAperture=Float.NaN;private var lastPhase=Float.NaN;private var lastAberration=Float.NaN
    fun update(dt:Float,searching:Boolean,motionDt:Float=dt){
        val radius=liveRadius
        val detailT=((log2(max(1.0,radius/scale))-1.4)/5.6).coerceIn(0.0,1.0)
        detail=(.10+1.65*detailT*detailT*(3-2*detailT)+settings.detailOffset).coerceIn(0.0,3.0).toFloat()
        // Значение ползунка — десятичное число HTML, промежуточная арифметика JS — double.
        if(lastAperture!=settings.aperture){lastAperture=settings.aperture;aperture=lastAperture.toString().toDouble()}
        if(lastPhase!=settings.phase){lastPhase=settings.phase;phase=lastPhase.toString().toDouble()}
        if(lastAberration!=settings.aberration){lastAberration=settings.aberration;aberration=lastAberration.toString().toDouble()}
        val step=dt.coerceIn(1f/240,.1f).toDouble();val t=time
        parallax.update(dt)
        // В игре motionDt идёт по часам симуляции: на паузе ноль. Кадровый dt
        // остаётся для автофокуса/экспозиции и управления камерой.
        if(settings.animate){val f=.6+.4*sin(t*.11);val motion=motionDt.coerceIn(0f,.1f).toDouble()
            flowX+=(.035*sin(t*.071)+.02*sin(t*.23))*f*motion;flowY+=.025*cos(t*.053)*f*motion}
        if(settings.cameraEffects){
            if(frameCount++%8==0)coverage=liveCoverage.coerceAtLeast(0.0)
            hunt+=((if(searching&&settings.autoFocus)1.0 else 0.0)-hunt)*(1-exp(-step/(if(searching).25 else .6)))
            val target=if(settings.darkField)1.10-.34*coverage else 1+.12*coverage
            exposure+=(target*(1+(if(settings.darkField).25 else .09)*hunt)-exposure)*(1-exp(-step/.8))
            for(i in 0..2){val amount=if(settings.darkField){if(i==0)-.03 else if(i==1)0.0 else .05}else{if(i==0).045 else if(i==1)-.035 else .03}
                whiteBalance[i]+=((1+amount*coverage)*(1+.012*sin(t*.05+i*2))-whiteBalance[i])*(1-exp(-step/3))}
        }else{exposure=1.0;whiteBalance.fill(1.0)}
    }
    internal fun uniform(name:String,a:FloatArray,stage:String,tw:Int,th:Int){
        val s=settings;val zoom=(.0085/scale).coerceIn(.7,2.3)
        when(name){
            "uCam"->{a[0]=camX.toFloat();a[1]=camY.toFloat()}
            "uRes"->{a[0]=tw.toFloat();a[1]=th.toFloat()}
            "uFull"->{a[0]=width.toFloat();a[1]=height.toFloat()}
            "uSpan"->{a[0]=(width*scale.toDouble()).toFloat();a[1]=(height*scale.toDouble()).toFloat()}
            "uScale"->a[0]=if(stage=="halo")scale.toFloat() else (scale*width/tw).toFloat()
            "uFocus"->a[0]=(if(stage=="environment-optics")focus-phase*.12 else focus).toFloat()
            "uTime"->a[0]=time.toFloat()
            "uFlow"->{a[0]=flowX.toFloat();a[1]=flowY.toFloat()}
            "uPar"->{a[0]=parallax.x;a[1]=parallax.y}
            "uCoc"->a[0]=(90*zoom).toFloat()
            "uRMax"->a[0]=min(30.0,14+6*zoom).toFloat()
            "uAperture"->a[0]=(aperture*165*zoom).toFloat()
            "uPixelRatio"->a[0]=ceil(width*s.layerScale).toFloat()/width
            "uCA"->a[0]=(if(stage=="total-optics")aberration else aberration*.035).toFloat()
            "uLayer"->if(stage=="muscle-pigment")a[0]=s.layer.toFloat()
            "uPhase"->a[0]=s.phase
            "uRefraction"->a[0]=s.refraction
            "uDensity"->a[0]=s.density
            "uDarkContrast"->a[0]=s.darkContrast
            "uDetailContrast"->a[0]=detail
            "uTissueBlur"->a[0]=s.tissueBlur
            "uReliefHeight"->a[0]=s.reliefHeight
            "uContourWidth"->a[0]=s.contourWidth
            "uCohesion"->a[0]=s.cohesion
            "uInflate"->a[0]=s.inflate
            "uInner"->a[0]=s.inner
            "uDebris"->a[0]=s.debris
            "uMode"->a[0]=if(s.darkField)1f else 0f
            "uColorFilter"->a[0]=if(s.colorFilter)1f else 0f
            "uTintStrength"->a[0]=s.tintStrength
            "uInspect"->a[0]=if(stage=="volume-background")0f else if(s.mask)2f else s.opticalView.toFloat()
            "uMask"->a[0]=if(s.mask||s.opticalView>0)1f else 0f
            "uVig"->a[0]=s.vignette
            "uDirt"->a[0]=if(s.dirt)1f else 0f
            "uCamFx"->a[0]=if(s.cameraEffects)1f else 0f
            "uExpo"->for(i in 0..2)a[i]=(exposure*whiteBalance[i]).toFloat()
        }
    }
}
