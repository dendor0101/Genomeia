package io.github.some_example_name.render.microscope

/** Ограничения пользовательских параметров — общие для команды и UI. */
enum class OpticalParameter(val key:String,val low:Float,val high:Float,val step:Float=.01f,val flag:Boolean=false) {
    QUALITY("layerScale",.25f,1f,.25f),
    SMOOTH_CONTOUR("smoothContour",0f,2f),
    TISSUE_LAYERS("tissueLayers",1f,3f,1f),
    LAYER("layer",-1f,3f,1f),
    VIEW("opticalView",0f,4f,1f),
    APERTURE("aperture",.3f,2f),
    FOCUS("cellFocus",-1f,1f),
    REFRACTION("refraction",0f,2f),
    PHASE("phase",-1f,1f),
    ABERRATION("aberration",0f,1f),
    VIGNETTE("vignette",0f,1f),
    HEIGHT("reliefHeight",0f,3f),
    CONTOUR("contourWidth",0f,3f),
    BLUR("tissueBlur",0f,6f,.1f),
    DARKNESS("darkContrast",0f,2.5f),
    DETAILS("detailOffset",-1.5f,1.5f),
    DENSITY("density",.15f,2f),
    DEBRIS("debris",0f,1f),
    AUTOFOCUS("autoFocus",0f,1f,1f,true),
    ANIMATE("animate",0f,1f,1f,true),
    DARKFIELD("darkField",0f,1f,1f,true),
    DIRT("dirt",0f,1f,1f,true),
    CAMERA("cameraEffects",0f,1f,1f,true),
    MASK("mask",0f,1f,1f,true),
    BOUNDED_OPTICS("boundedOptics",0f,1f,1f,true),
    HALF_GEOMETRY("halfGeometry",0f,1f,1f,true),
    CACHE_PAUSED("cachePaused",0f,1f,1f,true),
    DEBUG_SPRINGS("debugSprings",0f,1f,1f,true),
    NO_ENVIRONMENT("noEnvironment",0f,1f,1f,true),
    PACKED_PSF("packedPsf",0f,1f,1f,true),
    TEXTURE_RESOLUTION("textureResolution",0f,1f,1f,true);

    fun get(s:MicroscopeSettings):Float = when(this){
        QUALITY -> s.layerScale
        SMOOTH_CONTOUR -> s.smoothContour
        TISSUE_LAYERS -> s.tissueLayers.toFloat()
        LAYER -> s.layer.toFloat()
        VIEW -> s.opticalView.toFloat()
        APERTURE -> s.aperture
        FOCUS -> s.cellFocus
        REFRACTION -> s.refraction
        PHASE -> s.phase
        ABERRATION -> s.aberration
        VIGNETTE -> s.vignette
        HEIGHT -> s.reliefHeight
        CONTOUR -> s.contourWidth
        BLUR -> s.tissueBlur
        DARKNESS -> s.darkContrast
        DETAILS -> s.detailOffset
        DENSITY -> s.density
        DEBRIS -> s.debris
        AUTOFOCUS -> if (s.autoFocus) 1f else 0f
        ANIMATE -> if (s.animate) 1f else 0f
        DARKFIELD -> if (s.darkField) 1f else 0f
        DIRT -> if (s.dirt) 1f else 0f
        CAMERA -> if (s.cameraEffects) 1f else 0f
        MASK -> if (s.mask) 1f else 0f
        BOUNDED_OPTICS -> if (s.boundedOptics) 1f else 0f
        HALF_GEOMETRY -> if (s.halfGeometry) 1f else 0f
        CACHE_PAUSED -> if (s.cachePaused) 1f else 0f
        DEBUG_SPRINGS -> if (s.debugSprings) 1f else 0f
        NO_ENVIRONMENT -> if (s.noEnvironment) 1f else 0f
        PACKED_PSF -> if (s.packedPsf) 1f else 0f
        TEXTURE_RESOLUTION -> if (s.textureResolution) 1f else 0f
    }

    fun set(s:MicroscopeSettings,value:Float){
        if(!value.isFinite())return
        val v=value.coerceIn(low,high)
        when(this){
            QUALITY -> s.layerScale = v
            SMOOTH_CONTOUR -> s.smoothContour = v
            TISSUE_LAYERS -> s.tissueLayers = v.toInt()
            LAYER -> s.layer = if(v<0f)-1 else v.toInt().coerceIn(1,3)
            VIEW -> s.opticalView = v.toInt()
            APERTURE -> s.aperture = v
            FOCUS -> s.cellFocus = v
            REFRACTION -> s.refraction = v
            PHASE -> s.phase = v
            ABERRATION -> s.aberration = v
            VIGNETTE -> s.vignette = v
            HEIGHT -> s.reliefHeight = v
            CONTOUR -> s.contourWidth = v
            BLUR -> s.tissueBlur = v
            DARKNESS -> s.darkContrast = v
            DETAILS -> s.detailOffset = v
            DENSITY -> s.density = v
            DEBRIS -> s.debris = v
            AUTOFOCUS -> s.autoFocus = v>.5f
            ANIMATE -> s.animate = v>.5f
            DARKFIELD -> s.darkField = v>.5f
            DIRT -> s.dirt = v>.5f
            CAMERA -> s.cameraEffects = v>.5f
            MASK -> s.mask = v>.5f
            BOUNDED_OPTICS -> s.boundedOptics = v>.5f
            HALF_GEOMETRY -> s.halfGeometry = v>.5f
            CACHE_PAUSED -> s.cachePaused = v>.5f
            DEBUG_SPRINGS -> s.debugSprings = v>.5f
            NO_ENVIRONMENT -> s.noEnvironment = v>.5f
            PACKED_PSF -> s.packedPsf = v>.5f
            TEXTURE_RESOLUTION -> s.textureResolution = v>.5f
        }
    }
}
