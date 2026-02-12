package io.github.some_example_name.old.genome_editor.dialog

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.SpriteDrawable
import com.kotcrab.vis.ui.VisUI

class CircleWidget(initialColor: Color, private val smallCircleRadius: Float, initialDirectedAngle: Float?) : Table() {
    private val shader: ShaderProgram
    private var outerColor: Color = initialColor
    private val innerColor: Color = Color.BLACK
    private var directedAngle: Float? = if (initialDirectedAngle != null) initialDirectedAngle * -1 else null
    private val whiteRegion: TextureRegion

    companion object {
        private const val VERTEX_SHADER = """
            #ifdef GL_ES
            #define ATTR attribute
            #define VAR_OUT varying
            #else
            #define ATTR attribute
            #define VAR_OUT varying
            #endif

            ATTR vec4 a_position;
            ATTR vec4 a_color;
            ATTR vec2 a_texCoord0;

            uniform mat4 u_projTrans;

            VAR_OUT vec4 v_color;
            VAR_OUT vec2 v_texCoord;

            void main() {
                v_color = a_color;
                v_texCoord = a_texCoord0;
                gl_Position = u_projTrans * a_position;
            }
        """

        private const val FRAGMENT_SHADER = """
            #ifdef GL_ES
            precision mediump float;
            #define VAR_IN varying
            #define FRAG_COLOR gl_FragColor
            #else
            #define VAR_IN varying
            #define FRAG_COLOR gl_FragColor
            #endif

            VAR_IN vec4 v_color;
            VAR_IN vec2 v_texCoord;

            uniform vec4 u_outerColor;
            uniform vec4 u_innerColor;
            uniform float u_outerRadius;
            uniform float u_innerRadius;
            uniform float u_arrowAngle;
            uniform bool u_drawArrow;

            float circle(vec2 uv, float radius) {
                float dist = length(uv - vec2(0.5, 0.5));
                float edgeWidth = 0.005;
                return 1.0 - smoothstep(radius - edgeWidth, radius + edgeWidth, dist);
            }

            float sdSegment(in vec2 p, in vec2 a, in vec2 b) {
                vec2 pa = p - a;
                vec2 ba = b - a;
                float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
                return length(pa - ba * h);
            }

            float sdTriangle(in vec2 p, in vec2 p0, in vec2 p1, in vec2 p2) {
                vec2 e0 = p1 - p0;
                vec2 e1 = p2 - p1;
                vec2 e2 = p0 - p2;
                vec2 v0 = p - p0;
                vec2 v1 = p - p1;
                vec2 v2 = p - p2;
                vec2 pq0 = v0 - e0 * clamp(dot(v0, e0) / dot(e0, e0), 0.0, 1.0);
                vec2 pq1 = v1 - e1 * clamp(dot(v1, e1) / dot(e1, e1), 0.0, 1.0);
                vec2 pq2 = v2 - e2 * clamp(dot(v2, e2) / dot(e2, e2), 0.0, 1.0);
                float s = sign(e0.x * e2.y - e0.y * e2.x);
                vec2 d = min(min(vec2(dot(pq0, pq0), s * (v0.x * e0.y - v0.y * e0.x)),
                                 vec2(dot(pq1, pq1), s * (v1.x * e1.y - v1.y * e1.x))),
                             vec2(dot(pq2, pq2), s * (v2.x * e2.y - v2.y * e2.x)));
                return -sqrt(d.x) * sign(d.y);
            }

            float getArrowMask(vec2 uv) {
                if (!u_drawArrow) {
                    return 0.0;
                }

                vec2 center = vec2(0.5, 0.5);
                vec2 p = uv - center;
                vec2 arrow_dir = vec2(cos(u_arrowAngle), sin(u_arrowAngle));
                vec2 perp = vec2(-arrow_dir.y, arrow_dir.x);

                float triangle_side = 0.03;
                float triangle_height = (sqrt(3.0) / 2.0) * triangle_side;
                float tip_distance = 0.24;
                float stem_length = tip_distance - triangle_height;
                vec2 stem_end = stem_length * arrow_dir;
                vec2 base_left = stem_end - (triangle_side / 2.0) * perp;
                vec2 base_right = stem_end + (triangle_side / 2.0) * perp;
                vec2 tip_pos = stem_end + triangle_height * arrow_dir;

                float stem_width = 0.01;
                float stem_half_width = stem_width / 2.0;

                float dist_line = sdSegment(p, vec2(0.0), stem_end);
                float sd_stem = dist_line - stem_half_width;
                float sd_head = sdTriangle(p, base_left, base_right, tip_pos);
                float sd_arrow = min(sd_stem, sd_head);

                float edgeWidth = 0.005;
                return 1.0 - smoothstep(-edgeWidth, edgeWidth, sd_arrow);
            }

            void main() {
                vec2 uv = v_texCoord;
                float outer = circle(uv, u_outerRadius);
                float shell = circle(uv, u_outerRadius + 0.05); // Shell slightly larger
                float inner = circle(uv, u_innerRadius);

                vec4 color = vec4(0.0, 0.0, 0.0, 0.0); // Transparent background outside circles
                color = mix(color, u_outerColor * 0.7, shell); // Outer shell
                color = mix(color, u_outerColor, outer); // Outer circle
                color = mix(color, u_innerColor, inner); // Inner circle

                // Arrow
                float arrow_mask = getArrowMask(uv);
                vec4 arrow_color = vec4(1.0, 1.0, 1.0, 1.0); // White color for arrow
                color = mix(color, arrow_color, arrow_mask);

                FRAG_COLOR = color;
            }
        """
    }

    init {
        // Prepare shaders with platform-specific #version
        var vertexCode = VERTEX_SHADER
        var fragmentCode = FRAGMENT_SHADER

        if (Gdx.app.type == Application.ApplicationType.Desktop) {
            vertexCode = "#version 120\n$vertexCode"
            fragmentCode = "#version 120\n$fragmentCode"
        }

        // Disable pedantic mode
        ShaderProgram.pedantic = false
        shader = ShaderProgram(vertexCode, fragmentCode)
        if (!shader.isCompiled) {
            Gdx.app.error("Shader", "Compilation failed: ${shader.log}")
            throw RuntimeException("Shader compilation failed: ${shader.log}")
        }

        setSize(100f, 100f) // Set widget size

        // Get TextureRegion from Drawable
        val drawable = VisUI.getSkin().getDrawable("white")
        whiteRegion = if (drawable is SpriteDrawable) {
            drawable.sprite
        } else {
            TextureRegion(Texture(Pixmap(1, 1, Pixmap.Format.RGBA8888).apply {
                setColor(Color.WHITE)
                fill()
            }))
        }
    }

    fun setCircleColor(newColor: Color) {
        outerColor = newColor
        invalidate()
    }

    fun setAngle(angle: Float?) {
        directedAngle = if (angle != null) angle * -1 else null
        invalidate()
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

        super.draw(batch, parentAlpha)

        val centerX = x + width / 2
        val centerY = y + height / 2
        val radius = minOf(width, height) / 2
        val widgetSize = radius * 2
        if (widgetSize <= 0f) return

        batch.shader = shader
        val innerRadNorm = smallCircleRadius / widgetSize * 1.5f
        val outerRadNorm = 0.2f  // Adjust if needed
        shader.setUniformf("u_outerColor", outerColor.r, outerColor.g, outerColor.b, 1.0f)
        shader.setUniformf("u_innerColor", innerColor.r, innerColor.g, innerColor.b, innerColor.a)
        shader.setUniformf("u_outerRadius", outerRadNorm)
        shader.setUniformf("u_innerRadius", innerRadNorm)
        shader.setUniformi("u_drawArrow", if (directedAngle != null) 1 else 0)
        shader.setUniformf("u_arrowAngle", directedAngle ?: 0f)

        batch.draw(whiteRegion, centerX - radius, centerY - radius, widgetSize, widgetSize)

        batch.shader = null

        Gdx.gl.glDisable(GL20.GL_BLEND)
    }

    fun dispose() {
        shader.dispose()
        if (VisUI.getSkin().getDrawable("white") !is SpriteDrawable) {
            whiteRegion.texture.dispose()
        }
    }
}
