package io.github.some_example_name.old.core.ui

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.I18NBundle
import com.kotcrab.vis.ui.widget.*
import io.github.some_example_name.old.game.MyGame
import io.github.some_example_name.old.game.applyCustomFontMedium
import io.github.some_example_name.render.microscope.MicroscopeSettings
import io.github.some_example_name.render.microscope.OpticalParameter
import java.util.Locale

/** Виджеты только отправляют команды; параметры принадлежат экрану симуляции. */
class TissueSettingsDialog(game:MyGame, bundle:I18NBundle, settings:MicroscopeSettings,
                          reset:()->Unit, change:(String,Float)->Unit):VisDialog(bundle.get("microscope.settings")) {
    private val textures=mutableListOf<Texture>()
    private val sliders=ArrayList<VisSlider>()
    private val refreshControls=ArrayList<()->Unit>()
    private var refreshing=false
    private var dragProgress = 0f

    override fun act(delta: Float) {
        super.act(delta)
        val step = delta / .18f
        dragProgress = (dragProgress + if (sliders.any { it.isDragging }) step else -step).coerceIn(0f, 1f)
    }

    override fun draw(batch: com.badlogic.gdx.graphics.g2d.Batch, parentAlpha: Float) {
        val opacity = 1f - .8f * com.badlogic.gdx.math.Interpolation.smooth.apply(dragProgress)
        super.draw(batch, parentAlpha * opacity)
    }

    private fun syncControls() {
        refreshing = true
        try { refreshControls.forEach { it() } } finally { refreshing = false }
    }

    init {
        isModal=true;isMovable=true;setupTitleSize(game)
        val d=Gdx.graphics.density
        val panelWidth=(minOf(720f*d,Gdx.graphics.width*.9f)-padLeft-padRight).coerceAtLeast(1f)
        val body=VisCompose.table {
            val pane=visScrollPane(
                cellInit={width(panelWidth).height(minOf(620f*d,Gdx.graphics.height*.70f))},
                scrollingDisabledX=true,fadeScrollBars=false
            ){
                pad(8f*d);defaults().growX().padBottom(12f*d)
                fun choice(row: VisTable, title: String, id: String, selected: () -> Boolean, select: () -> Unit) {
                    val button = makeStyledButton(title, game, textures, toggle = true)
                    button.name = id
                    button.isChecked = selected()
                    button.addListener(object : ChangeListener() {
                        override fun changed(event: ChangeEvent, actor: Actor) {
                            if (!refreshing) {
                                select()
                                syncControls()
                            }
                        }
                    })
                    row.add(button).uniformX().growX().minWidth(0f).pad(2f*d)
                    refreshControls.add { button.isChecked = selected() }
                }
                add(VisLabel(bundle.get("microscope.presets")).also { game.applyCustomFontMedium(it); it.color = STYLE_BEIGE }).left().row()
                val presets = VisTable()
                val scales = floatArrayOf(.25f, .5f, 1f)
                for ((index, name) in listOf("low", "medium", "high").withIndex()) {
                    val layers = index + 1
                    val scale = scales[index]
                    choice(presets, bundle.get("microscope.$name"), "microscope-preset-$name",
                        { settings.tissueLayers == layers && settings.layerScale == scale }) {
                        change(OpticalParameter.TISSUE_LAYERS.key, layers.toFloat())
                        change(OpticalParameter.QUALITY.key, scale)
                    }
                }
                add(presets).growX().row()
                for(p in OpticalParameter.entries){
                    if(p==OpticalParameter.TISSUE_LAYERS){
                        add(VisLabel(bundle.get("microscope.tissueLayers")).also{game.applyCustomFontMedium(it);it.color=STYLE_BEIGE}).left().row()
                        val row=VisTable()
                        for(n in 1..3){
                            choice(row, n.toString(), "microscope-tissueLayers-$n", { settings.tissueLayers == n }) {
                                change(p.key, n.toFloat())
                            }
                        }
                        add(row).growX().row();continue
                    }
                    val title=bundle.get("microscope.${p.key}")
                    val choices=when(p){
                        OpticalParameter.QUALITY -> arrayOf("25%","50%","100%")
                        OpticalParameter.LAYER -> arrayOf("all","lower","center","upper").map { bundle.get("microscope.$it") }.toTypedArray()
                        OpticalParameter.VIEW -> arrayOf(bundle.get("microscope.image"),"OPD",bundle.get("microscope.transmission"),bundle.get("microscope.depth"),bundle.get("microscope.height"))
                        else -> null
                    }
                    if(choices!=null){
                        val values=when(p){OpticalParameter.QUALITY->floatArrayOf(.25f,.5f,1f);OpticalParameter.LAYER->floatArrayOf(-1f,1f,2f,3f);else->floatArrayOf(0f,1f,2f,3f,4f)}
                        val label=VisLabel(title).also{game.applyCustomFontMedium(it);it.color=STYLE_BEIGE;it.setWrap(true)}
                        add(label).left().row()
                        val control=styledSelectBox(choices,values.indexOfFirst { it==p.get(settings) }.coerceAtLeast(0)){_,index->if(!refreshing){change(p.key,values[index]);syncControls()}}
                        control.name="microscope-${p.key}"
                        refreshControls.add{control.selectedIndex=values.indexOfFirst{it==p.get(settings)}.coerceAtLeast(0)}
                        add(control).row()
                    }else if(p.flag){
                        val control=makeStyledButton(title,game,textures,toggle=true).apply{
                            name="microscope-${p.key}";isChecked=(p.get(settings)>.5f) xor (p==OpticalParameter.NO_ENVIRONMENT)
                            label.setWrap(true);label.setAlignment(Align.center)
                            addListener(object:ChangeListener(){override fun changed(event:ChangeEvent?,actor:Actor?){if(!refreshing)change(p.key,if(isChecked xor (p==OpticalParameter.NO_ENVIRONMENT))1f else 0f)}})
                        }
                        refreshControls.add{control.isChecked=(p.get(settings)>.5f) xor (p==OpticalParameter.NO_ENVIRONMENT)}
                        add(control).minWidth(0f).row()
                    }else{
                        fun formatted(v:Float)=if(p==OpticalParameter.SMOOTH_CONTOUR)String.format(Locale.ROOT,"%.0f%%",v*100f) else String.format(Locale.ROOT,"%.2f",v)
                        val number=VisLabel(formatted(p.get(settings))).also{game.applyCustomFontMedium(it);it.color=STYLE_BEIGE}
                        val heading=VisCompose.table{
                            val label=VisLabel(title).also{game.applyCustomFontMedium(it);it.color=STYLE_BEIGE;it.setWrap(true)}
                            add(label).growX().left().padRight(8f*d)
                            add(number).minWidth(68f*d).right()
                        }
                        add(heading).padBottom(4f*d).row()
                        // Штатный ползунок игры отключает flickScroll родителя на время
                        // перетаскивания и возвращает его при отпускании/отмене касания.
                        val control=makeStyledSlider(p.low,p.high,p.step,false,textures).apply{
                            name="microscope-${p.key}";value=p.get(settings)
                            addListener(object:ChangeListener(){override fun changed(event:ChangeEvent?,actor:Actor?){
                                if(!refreshing)change(p.key,value);number.setText(formatted(value))
                            }})
                        }
                        refreshControls.add{control.value=p.get(settings);number.setText(formatted(p.get(settings)))}
                        sliders.add(control);add(control).height(32f*d).padBottom(16f*d).row()
                    }
                }
            }
            pane.name="microscope-scroll"
            pane.addCaptureListener(object:InputListener(){
                override fun touchDown(event:InputEvent,x:Float,y:Float,pointer:Int,button:Int):Boolean{
                    if(sliders.any{event.target.isDescendantOf(it)}){
                        // Уже начатая инерция не должна двигать дорожку под пальцем.
                        pane.fling(0f,0f,0f);pane.scrollY=pane.visualScrollY;pane.updateVisualScroll()
                    }
                    return false
                }
                override fun scrolled(event:InputEvent,x:Float,y:Float,amountX:Float,amountY:Float):Boolean{
                    if(!sliders.any{it.isDragging})return false
                    event.stop();return true
                }
            })
        }
        contentTable.add(body).grow()
        val defaults=makeStyledButton(bundle.get("microscope.reset"),game,textures)
        defaults.name="microscope-reset"
        defaults.addListener(object:ChangeListener(){override fun changed(event:ChangeEvent?,actor:Actor?){
            reset()
            // Одна команда сброса; обновление виджетов не создаёт десятки команд.
            syncControls()
        }})
        buttonsTable.add(defaults).minWidth(160f*d).padTop(8f*d).padRight(8f*d)
        val back=makeStyledButton(bundle.get("button.back"),game,textures)
        back.addListener(object:ChangeListener(){override fun changed(event:ChangeEvent?,actor:Actor?){fadeOut()}})
        buttonsTable.add(back).minWidth(160f*d).padTop(8f*d)
        closeOnEscape();pack();centerWindow()
    }
    override fun remove():Boolean {
        val removed=super.remove()
        textures.forEach{it.dispose()};textures.clear()
        return removed
    }
}
