package io.github.some_example_name.old.features.settings

import com.badlogic.gdx.Preferences
import io.github.some_example_name.render.microscope.MicroscopeSettings
import io.github.some_example_name.render.microscope.OpticalParameter
import java.lang.reflect.Proxy
import kotlin.test.*

class OpticalPreferencesTest {
    @Test fun contourSliderSavesFractionalStrength(){
        val b=Backend();val store=OpticalPreferences({b.prefs},{false});val s=MicroscopeSettings.gameDefaults()
        OpticalParameter.SMOOTH_CONTOUR.set(s,1.37f)
        store.stage(s);store.flush(force=true)
        assertEquals(1.37f,OpticalPreferences({Backend(b.disk).prefs},{false}).load()!!.smoothContour)
    }
    /** Раздельные память/диск: новый экземпляр видит только последний flush. */
    private class Backend(val disk:MutableMap<String,Any> = HashMap()) {
        val memory=HashMap(disk);var flushes=0
        val prefs=Proxy.newProxyInstance(Preferences::class.java.classLoader,arrayOf(Preferences::class.java)){proxy,method,args->
            when(method.name){
                "contains"->memory.containsKey(args!![0])
                "getFloat"->(memory[args!![0]]?:0f) as Float
                "putFloat"->{memory[args!![0] as String]=args[1];proxy}
                "flush"->{disk.clear();disk.putAll(memory);flushes++;null}
                else->error(method.name)
            }
        } as Preferences
    }
    @Test fun valuesSurviveNewStoreIncludingReset(){
        val b=Backend();val store=OpticalPreferences({b.prefs},{false});val s=MicroscopeSettings.gameDefaults()
        for(p in OpticalParameter.entries)p.set(s,p.high)
        store.stage(s);store.flush(force=true)
        val restarted=OpticalPreferences({Backend(b.disk).prefs},{false}).load()!!
        for(p in OpticalParameter.entries)assertEquals(p.get(s),p.get(restarted),p.key)
        store.stage(MicroscopeSettings.gameDefaults());store.flush(force=true)
        val reset=OpticalPreferences({Backend(b.disk).prefs},{false}).load()!!
        for(p in OpticalParameter.entries)assertEquals(p.get(MicroscopeSettings.gameDefaults()),p.get(reset),p.key)
    }
    @Test fun draggingDoesNotFlushAndLeavingFlushesImmediately(){
        val b=Backend();val store=OpticalPreferences({b.prefs},{false});val s=MicroscopeSettings.gameDefaults()
        repeat(100){s.refraction=it/100f;store.stage(s,OpticalParameter.REFRACTION);store.flush(.016f)}
        assertEquals(0,b.flushes);assertTrue(b.disk.isEmpty())
        store.flush(.2f);assertEquals(0,b.flushes)
        store.flush(.2f);assertEquals(1,b.flushes)
        store.flush(10f);assertEquals(1,b.flushes)
        store.stage(s,OpticalParameter.REFRACTION);store.flush(force=true);assertEquals(2,b.flushes)
    }
    @Test fun replayNeverTouchesThePreferencesProvider(){
        val store=OpticalPreferences({error("Replay accessed local preferences")},{true},{error("Replay accessed legacy preferences")})
        assertNull(store.load());store.stage(MicroscopeSettings.gameDefaults());store.flush(100f,force=true)
    }
    @Test fun migrationIsOneTimeAndLegacyWritesCannotResetShaderSettings(){
        val old=Backend();val current=Backend()
        old.memory["microscope.v1.phase"]=-.42f
        old.memory["microscope.v1.packedPsf"]=1f
        current.memory["microscope.v1.phase"]=.73f
        val migrated=OpticalPreferences({current.prefs},{false},{old.prefs}).load()!!
        assertEquals(.73f,migrated.phase);assertTrue(migrated.packedPsf)
        assertEquals(1,current.flushes);assertEquals(0,old.flushes)
        // Другая ветка сохраняет устаревшую копию общего файла.
        old.memory["microscope.v1.phase"]=0f;old.memory["microscope.v1.packedPsf"]=0f
        val restart=Backend(current.disk)
        val loaded=OpticalPreferences({restart.prefs},{false},{error("Migration repeated")}).load()!!
        assertEquals(.73f,loaded.phase);assertTrue(loaded.packedPsf)
        for(p in OpticalParameter.entries)assertEquals(p.get(migrated),p.get(loaded),p.key)
        assertEquals(0,restart.flushes)
    }
    @Test fun removedExperimentsDoNotChangeDefaults(){
        val b=Backend();b.memory["microscope.v1.reducedPsf"]=1f
        b.memory["microscope.v1.computeResolve"]=1f
        val loaded=OpticalPreferences({b.prefs},{false}).load()!!
        val defaults=MicroscopeSettings.gameDefaults()
        for(p in OpticalParameter.entries)assertEquals(p.get(defaults),p.get(loaded))
        assertNull(OpticalParameter.entries.firstOrNull{it.key=="reducedPsf"})
    }
    @Test fun corruptAndMissingKeysRetainDefaultsAndBoundsAreApplied(){
        val b=Backend();b.memory["microscope.v1.refraction"]=Float.NaN
        b.memory["microscope.v1.density"]=100f;b.memory["microscope.v1.phase"]="broken"
        b.memory["microscope.v1.removedOption"]=1f
        val s=OpticalPreferences({b.prefs},{false}).load()!!;val defaults=MicroscopeSettings.gameDefaults()
        assertEquals(defaults.refraction,s.refraction);assertEquals(defaults.phase,s.phase)
        assertEquals(OpticalParameter.DENSITY.high,s.density);assertEquals(defaults.aperture,s.aperture)
    }
}
