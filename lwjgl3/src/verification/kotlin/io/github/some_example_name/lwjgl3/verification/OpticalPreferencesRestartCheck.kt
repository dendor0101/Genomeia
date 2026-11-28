package io.github.some_example_name.lwjgl3.verification

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Preferences
import com.badlogic.gdx.files.FileHandle
import io.github.some_example_name.old.features.settings.UserPreferences
import io.github.some_example_name.render.microscope.MicroscopeSettings
import io.github.some_example_name.render.microscope.OpticalParameter
import java.io.File
import java.lang.reflect.Proxy

/** Реальный libGDX XML + четыре отдельных JVM. Настройки игрока не открываются. */
fun main(args:Array<String>){
    if(args.isEmpty()){
        val dir=java.nio.file.Files.createTempDirectory("genomeia-preferences-restart-").toFile()
        for(phase in arrayOf("write","read","reset","defaults")){
            val process=ProcessBuilder(File(System.getProperty("java.home"),"bin/java").path,
                "-cp",System.getProperty("java.class.path"),
                "io.github.some_example_name.lwjgl3.verification.OpticalPreferencesRestartCheckKt",dir.path,phase)
                .inheritIO().start()
            check(process.waitFor()==0){"Preferences restart failed at $phase"}
        }
        println("PREFERENCES RESTART PASS: ${OpticalParameter.entries.size} settings, migration, legacy overwrite, reset; four JVMs; $dir")
        return
    }
    val dir=File(args[0]);val phase=args[1]
    val stores=HashMap<String,Lwjgl3Preferences>()
    Gdx.app=Proxy.newProxyInstance(Application::class.java.classLoader,arrayOf(Application::class.java)){_,method,values->
        when(method.name){
            "getPreferences"->stores.getOrPut(values!![0] as String){Lwjgl3Preferences(FileHandle(File(dir,values[0] as String)))}
            "getType"->Application.ApplicationType.Desktop
            else->error("Unexpected app access: ${method.name}")
        }
    } as Application
    val expected=MicroscopeSettings.gameDefaults()
    for(p in OpticalParameter.entries)p.set(expected,if(p.flag){if(p.ordinal%2==0)1f else 0f}else p.low+(p.high-p.low)*.75f)
    if(phase=="write"){
        val old=Gdx.app.getPreferences("genomeia")
        for(p in OpticalParameter.entries)old.putFloat("microscope.v1.${p.key}",p.get(expected))
        old.flush()
    }
    if(phase=="read"){
        val old=Gdx.app.getPreferences("genomeia")
        for(p in OpticalParameter.entries)old.putFloat("microscope.v1.${p.key}",p.get(MicroscopeSettings.gameDefaults()))
        old.flush() // Старая ветка записала устаревшие настройки в общий файл.
    }
    val loaded=UserPreferences.loadOptical()!!
    if(phase=="defaults")for(p in OpticalParameter.entries)check(p.get(loaded)==p.get(MicroscopeSettings.gameDefaults())){"Default ${p.key}"}
    else for(p in OpticalParameter.entries)check(p.get(loaded)==p.get(expected)){"Lost ${p.key}: ${p.get(loaded)} != ${p.get(expected)}"}
    if(phase=="write"){
        for(p in OpticalParameter.entries)UserPreferences.saveOptical(loaded,p)
        UserPreferences.flushOptical(force=true)
    }else if(phase=="reset"){
        UserPreferences.saveOptical(MicroscopeSettings.gameDefaults());UserPreferences.flushOptical(force=true)
    }
    println("Preferences $phase PASS")
}
