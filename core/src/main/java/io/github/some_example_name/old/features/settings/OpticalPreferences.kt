package io.github.some_example_name.old.features.settings

import com.badlogic.gdx.Preferences
import io.github.some_example_name.render.microscope.MicroscopeSettings
import io.github.some_example_name.render.microscope.OpticalParameter

/** Холодный ввод/вывод настроек. Провайдер вызывается только ПОСЛЕ проверки повтора.
 * Ползунок меняет память Preferences; диск — через 350 мс покоя или при уходе с экрана.
 * Имена параметров устойчивы к перестановке enum и добавлению новых настроек. */
internal class OpticalPreferences(private val storage:()->Preferences,private val replay:()->Boolean,
                                  private val legacy:(()->Preferences)?=null) {
    private val keys=OpticalParameter.entries.map{"microscope.v1.${it.key}"}
    private var dirty=false
    private var delay=0f
    fun load():MicroscopeSettings? {
        if(replay())return null
        val p=storage();val result=MicroscopeSettings.gameDefaults()
        // Однократная миграция: общие настройки старой ветки больше не могут
        // перезаписать оптику. Старый файл только читаем, новый имеет приоритет.
        val migrate=legacy!=null&&!p.contains("microscope.migrated")
        val old=if(migrate)legacy?.invoke() else null
        for(parameter in OpticalParameter.entries){
            val key=keys[parameter.ordinal]
            val source=if(p.contains(key))p else old
            if(source?.contains(key)==true){
                // Повреждённый/старый параметр не должен мешать загрузке остальных.
                val value=try{source.getFloat(key)}catch(_:RuntimeException){continue}
                parameter.set(result,value)
            }
        }
        if(migrate){
            for(parameter in OpticalParameter.entries)p.putFloat(keys[parameter.ordinal],parameter.get(result))
            p.putFloat("microscope.migrated",1f);p.flush()
        }
        return result
    }
    fun stage(settings:MicroscopeSettings,parameter:OpticalParameter?=null){
        if(replay())return
        val p=storage()
        if(parameter!=null)p.putFloat(keys[parameter.ordinal],parameter.get(settings))
        else for(entry in OpticalParameter.entries)p.putFloat(keys[entry.ordinal],entry.get(settings))
        dirty=true;delay=.35f
    }
    fun flush(delta:Float=0f,force:Boolean=false){
        if(replay()||!dirty)return
        if(delta.isFinite()&&delta>0f)delay-=delta
        if(!force&&delay>0f)return
        storage().flush();dirty=false
    }
}
