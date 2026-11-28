package io.github.some_example_name.render.microscope

/** Объединяем оптическую массу, затем один раз фокусируем группу. */
internal object TissueLayerGroups {
    fun owner(slice:Int,count:Int):Int=if(slice !in 1..3||count==3)slice else if(count==1)2 else if(slice==2)1 else slice
    fun depth(slice:Int,count:Int):Float=if(count==2&&slice==1)1.5f else slice.toFloat()
    fun first(slice:Int,count:Int):Int=if(count==1&&slice==2)1 else slice
    fun size(slice:Int,count:Int):Int=if(count==1&&slice==2)3 else if(count==2&&slice==1)2 else 1
}
