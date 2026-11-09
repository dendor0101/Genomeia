package io.github.some_example_name.old.core.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable

/** Та же рамка 64×64, но с покрытием 4×4 для плавных внешнего и внутреннего контуров. */
fun makeSmoothStyledNP(fill: Color, border: Color, textures: MutableList<Texture>): NinePatchDrawable {
    val size = 64
    val radius = 14
    val pixmap = Pixmap(size, size, Pixmap.Format.RGBA8888)
    pixmap.blending = Pixmap.Blending.None
    // Центры скруглений совпадают у рамки и заливки: толщина окантовки равномерна.
    for (y in 0 until size) for (x in 0 until size) {
        var outer = 0
        var inner = 0
        for (sy in 0 until 4) for (sx in 0 until 4) {
            val px = x + (sx + 0.5f) / 4f
            val py = y + (sy + 0.5f) / 4f
            val dx = px - px.coerceIn(14f, 50f)
            val dy = py - py.coerceIn(14f, 50f)
            val distanceSquared = dx * dx + dy * dy
            if (distanceSquared <= 14f * 14f) outer++
            if (distanceSquared <= 12f * 12f && px >= 2f && px <= 62f && py >= 2f && py <= 62f) inner++
        }
        val fillAlpha = inner * fill.a
        val borderAlpha = (outer - inner) * border.a
        val alpha = fillAlpha + borderAlpha
        // Усредняем с учётом альфы: прозрачные пиксели не оставляют чёрный ореол.
        val red = if (alpha > 0f) (fill.r * fillAlpha + border.r * borderAlpha) / alpha else border.r
        val green = if (alpha > 0f) (fill.g * fillAlpha + border.g * borderAlpha) / alpha else border.g
        val blue = if (alpha > 0f) (fill.b * fillAlpha + border.b * borderAlpha) / alpha else border.b
        pixmap.drawPixel(x, y, Color.rgba8888(red, green, blue, alpha / 16f))
    }
    val texture = Texture(pixmap).apply {
        setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
    }
    pixmap.dispose()
    textures += texture
    return NinePatchDrawable(NinePatch(texture, radius, radius, radius, radius))
}
