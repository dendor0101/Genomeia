package io.github.some_example_name.old.systems.render

import com.badlogic.gdx.Gdx

/**
 * Loads an OpenGL ES (GLSL ES 3.x) shader file and rewrites it for the macOS desktop OpenGL 4.1
 * core profile. macOS cannot compile `#version ... es` shaders and rejects the ES-only default
 * `precision ...;` statements / `highp|mediump|lowp` qualifiers. The transform is purely textual
 * and preserves semantics (desktop GLSL defaults to highp everywhere).
 *
 * Used only by the macOS VBO renderers ([ShaderManagerDesktopVbo] / [PheromoneShaderManagerDesktopVbo]),
 * so the existing `_android`/shared shader files stay the single source of truth and Android is unaffected.
 */
object DesktopShaderSource {
    private val versionEs = Regex("""(?m)^#version\s+\d+\s+es\b.*$""")
    private val precisionLine = Regex("""(?m)^[ \t]*precision\s+(?:highp|mediump|lowp)\s+\w+\s*;.*$""")
    private val precisionQualifier = Regex("""\b(?:highp|mediump|lowp)\b""")

    fun load(path: String): String {
        val src = Gdx.files.internal(path).readString()
        return src
            .replace(versionEs, "#version 410")
            .replace(precisionLine, "")
            .replace(precisionQualifier, "")
    }
}
