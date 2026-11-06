package io.github.some_example_name.render.microscope

class MicroscopeSettings {
    companion object {
        /** Профиль игры, используемый также при сбросе настроек. */
        fun gameDefaults()=MicroscopeSettings().apply{
            layerScale=1f; aperture=.94f; refraction=.93f; cellFocus=-.13f
            phase=.71999997f; vignette=.44f; aberration=1f
            reliefHeight=.66999996f; contourWidth=.41f; darkContrast=.71999997f
            detailOffset=-.08f; density=.98999995f
            textureResolution=true
            boundedOptics=true
            halfGeometry=true
            cachePaused=true
            cameraEffects=true
            noEnvironment=false
        }
    }
    var layerScale = .5f
    var layer = -1
    var opticalView = 0
    var aperture = 1.07f
    var refraction = 1.03f
    var cellFocus = .01f
    var phase = .52f
    var vignette = 1f
    var aberration = .73f
    var inflate = .34f
    var inner = .3f
    var reliefHeight = 1f
    var contourWidth = 1f
    var tissueBlur = 0f
    var darkContrast = 1f
    var detailOffset = 0f
    var density = .94f
    var tintStrength = .12f
    var cohesion = .86f
    var debris = .16f
    var noEnvironment = false
    var darkField = false
    var animate = true
    var colorFilter = true
    var tissueLayers = 3
    var smoothContour = 0f
    var cachePaused = false
    var halfGeometry = false
    var boundedOptics = false
    var packedPsf = false
    var textureResolution = false
    /** Source texel size in selected-resolution pixels. Verification may sweep this threshold. */
    var textureResolutionThreshold = 32f
    var dirt = true
    var cameraEffects = true
    var mask = false
    var autoFocus = true
    var debugSprings = false

}
