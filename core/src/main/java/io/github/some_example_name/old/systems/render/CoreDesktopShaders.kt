package io.github.some_example_name.old.systems.render

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.viewport.Viewport

/**
 * macOS runs on an OpenGL 4.1 core-profile context (its hard ceiling — Apple deprecated OpenGL and never shipped 4.3).
 * A core-profile context rejects libGDX's built-in default SpriteBatch/ShapeRenderer shaders, which are GLES2-style
 * (attribute/varying, no #version). Those shaders only compile on a legacy 2.1 context, but a 2.1 context has no
 * texture arrays / instancing, which the GPU renderers need.
 *
 * So on macOS we request a 4.1 core context (see Lwjgl3Launcher GL30 emulation) and supply these core-compatible
 * (#version 150) replacements whenever the app builds a SpriteBatch / ShapeRenderer / Stage. On every other platform
 * the libGDX defaults are returned unchanged, so Windows/Linux/Android behaviour is untouched.
 */
object CoreDesktopShaders {

    private fun isMac(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("mac")

    /** True only on macOS desktop — the one platform that needs the core-compatible default shaders. */
    val enabled: Boolean
        get() = isMac() && Gdx.app.type == Application.ApplicationType.Desktop

    private val batchShader: ShaderProgram by lazy { compile(batchVert, batchFrag) }
    private val shapeShader: ShaderProgram by lazy { compile(shapeVert, shapeFrag) }

    fun newBatch(): SpriteBatch =
        if (enabled) SpriteBatch(1000, batchShader) else SpriteBatch()

    fun newShapeRenderer(): ShapeRenderer =
        if (enabled) ShapeRenderer(5000, shapeShader) else ShapeRenderer()

    fun newStage(viewport: Viewport): Stage =
        if (enabled) Stage(viewport, newBatch()) else Stage(viewport)

    private fun compile(vert: String, frag: String): ShaderProgram {
        val program = ShaderProgram(vert, frag)
        check(program.isCompiled) { "Core desktop shader failed to compile: ${program.log}" }
        return program
    }

    // SpriteBatch default shader, ported to desktop core-profile GLSL 1.50.
    // Attribute / uniform names must match libGDX's SpriteBatch exactly (a_position, a_color, a_texCoord0, u_projTrans, u_texture).
    private val batchVert = """
        #version 150
        in vec4 a_position;
        in vec4 a_color;
        in vec2 a_texCoord0;
        uniform mat4 u_projTrans;
        out vec4 v_color;
        out vec2 v_texCoords;
        void main() {
            v_color = a_color;
            v_color.a = v_color.a * (255.0/254.0);
            v_texCoords = a_texCoord0;
            gl_Position = u_projTrans * a_position;
        }
    """.trimIndent()

    private val batchFrag = """
        #version 150
        in vec4 v_color;
        in vec2 v_texCoords;
        uniform sampler2D u_texture;
        out vec4 fragColor;
        void main() {
            fragColor = v_color * texture(u_texture, v_texCoords);
        }
    """.trimIndent()

    // ShapeRenderer (ImmediateModeRenderer20) default shader, ported to desktop core-profile GLSL 1.50.
    private val shapeVert = """
        #version 150
        in vec4 a_position;
        in vec4 a_color;
        uniform mat4 u_projModelView;
        out vec4 v_color;
        void main() {
            v_color = a_color;
            v_color.a = v_color.a * (255.0/254.0);
            gl_Position = u_projModelView * a_position;
        }
    """.trimIndent()

    private val shapeFrag = """
        #version 150
        in vec4 v_color;
        out vec4 fragColor;
        void main() {
            fragColor = v_color;
        }
    """.trimIndent()
}
