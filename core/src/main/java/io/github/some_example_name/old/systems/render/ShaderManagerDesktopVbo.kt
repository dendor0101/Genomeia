package io.github.some_example_name.old.systems.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL30
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.BufferUtils
import io.github.some_example_name.old.systems.render.RenderSystem.Companion.INITIAL_PARTICLE_CAPACITY
import io.github.some_example_name.old.systems.render.RenderSystem.Companion.PARTICLE_STRUCT_SIZE
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

/**
 * macOS desktop renderer for the tissue/particle layer.
 *
 * macOS caps OpenGL at 4.1 (no SSBOs), so it cannot run [ShaderManagerLibgdxApi] (which uses 4.3
 * SSBOs). This is a desktop port of the Android VBO renderer: identical VBO + instanced-attribute
 * pipeline, but issuing GL through libGDX's `Gdx.gl30` (`GL30` extends `GL20`, so it covers every
 * call used here) instead of `android.opengl.GLES32`, and loading the same `_android`/shared shaders
 * through [DesktopShaderSource] (ES GLSL -> desktop 4.1). Windows/Linux keep the SSBO renderer.
 *
 * Important: on the desktop backend all GL buffers handed to LWJGL (gen/delete/iv-queries) MUST be
 * direct (`BufferUtils.newIntBuffer`); heap buffers (`IntBuffer.allocate`/`wrap`) crash the JVM
 * natively on macOS. GL object ids are stored as plain ints.
 */
class ShaderManagerDesktopVbo : ShaderManager {

    private val gl get() = Gdx.gl30!!

    /** Direct single-int buffer for gen / iv-query calls (read result via .get(0)). */
    private fun oneInt(): IntBuffer = BufferUtils.newIntBuffer(1)
    /** Direct single-int buffer wrapping [id], for delete calls. */
    private fun oneInt(id: Int): IntBuffer = BufferUtils.newIntBuffer(1).also { it.put(id); it.flip() }

    // Particle VBO (вместо SSBO)
    private var particleVbo = 0
    private var particleVboCapacity = INITIAL_PARTICLE_CAPACITY * PARTICLE_STRUCT_SIZE

    // Шейдеры
    private var particleProgram = 0
    private var sobelProgram = 0
    private var distortProgram = 0
    private var blurProgram = 0

    // Uniform locations
    private var particleProjLoc = 0
    private var particleTextureScaleLoc = 0
    private var particleColorScaleLoc = 0
    private var particleTextureArrayLoc = 0

    private var sobelTextureLoc = 0
    private var sobelResolutionLoc = 0
    private var sobelZoomLoc = 0
    private var vignetteEnabledLoc = 0

    private var distortTextureLoc = 0
    private var distortResolutionLoc = 0

    private var blurTextureLoc = 0
    private var blurAmountLoc = 0
    private var blurResolutionLoc = 0

    // Quad VAO + VBO (один на всё)
    private var quadVao = 0
    private var quadVbo = 0

    // Texture Array
    private var textureArray = 0
    private var numLayers = 0

    // FBOs
    private var sceneFbo = 0
    private var sceneColorTex = 0
    private var sceneDepthRbo = 0
    private var sceneFboWidth = 0
    private var sceneFboHeight = 0

    private var blurFbo = 0
    private var blurColorTex = 0
    private var blurFboWidth = 0
    private var blurFboHeight = 0

    private var distortFbo = 0
    private var distortColorTex = 0
    private var distortFboWidth = 0
    private var distortFboHeight = 0

    private fun compileShader(type: Int, source: String): Int {
        val shader = gl.glCreateShader(type)
        gl.glShaderSource(shader, source)
        gl.glCompileShader(shader)

        val status = oneInt()
        gl.glGetShaderiv(shader, GL30.GL_COMPILE_STATUS, status)
        if (status.get(0) == 0) {
            val log = gl.glGetShaderInfoLog(shader)
            gl.glDeleteShader(shader)
            throw RuntimeException("Shader compile error: $log")
        }
        return shader
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = compileShader(GL30.GL_VERTEX_SHADER, vertexSource)
        val fs = compileShader(GL30.GL_FRAGMENT_SHADER, fragmentSource)

        val program = gl.glCreateProgram()
        gl.glAttachShader(program, vs)
        gl.glAttachShader(program, fs)
        gl.glLinkProgram(program)

        val status = oneInt()
        gl.glGetProgramiv(program, GL30.GL_LINK_STATUS, status)
        if (status.get(0) == 0) {
            val log = gl.glGetProgramInfoLog(program)
            gl.glDeleteProgram(program)
            throw RuntimeException("Program link error: $log")
        }

        gl.glDeleteShader(vs)
        gl.glDeleteShader(fs)
        return program
    }

    private fun createTextureArray() {
        numLayers = texturePaths.size
        if (numLayers == 0) throw IllegalStateException("Нет текстур для TextureArray!")

        val pixmaps = texturePaths.map { path ->
            val file = Gdx.files.internal(path)
            if (!file.exists()) throw IllegalArgumentException("Текстура не найдена: $path")
            Pixmap(file)
        }

        val width = pixmaps[0].width
        val height = pixmaps[0].height

        for (p in pixmaps) {
            if (p.width != width || p.height != height) {
                throw IllegalStateException("Все текстуры в TextureArray должны быть одного размера! (${width}×${height})")
            }
        }

        val buffer = oneInt()
        gl.glGenTextures(1, buffer)
        textureArray = buffer.get(0)

        gl.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, textureArray)

        gl.glTexImage3D(
            GL30.GL_TEXTURE_2D_ARRAY, 0, GL30.GL_RGBA8,
            width, height, numLayers, 0,
            GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE, null
        )

        for ((layer, pixmap) in pixmaps.withIndex()) {
            gl.glTexSubImage3D(
                GL30.GL_TEXTURE_2D_ARRAY, 0,
                0, 0, layer,
                pixmap.width, pixmap.height, 1,
                GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE,
                pixmap.pixels
            )
            pixmap.dispose()
        }

        gl.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY)

        gl.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_MIN_FILTER, GL30.GL_LINEAR_MIPMAP_LINEAR)
        gl.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_MAG_FILTER, GL30.GL_LINEAR)
        gl.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_WRAP_S, GL30.GL_REPEAT)
        gl.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL30.GL_TEXTURE_WRAP_T, GL30.GL_REPEAT)

        gl.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0)

        println("✅ TextureArray создан (desktop VBO): $numLayers слоёв, ${width}×${height} px")
    }

    private fun createParticleVbo() {
        val b = oneInt()
        gl.glGenBuffers(1, b)
        particleVbo = b.get(0)
        gl.glBindBuffer(GL30.GL_ARRAY_BUFFER, particleVbo)
        gl.glBufferData(
            GL30.GL_ARRAY_BUFFER,
            INITIAL_PARTICLE_CAPACITY * PARTICLE_STRUCT_SIZE,
            null,
            GL30.GL_DYNAMIC_DRAW
        )
        gl.glBindBuffer(GL30.GL_ARRAY_BUFFER, 0)
        println("✅ Particle VBO создан (desktop VBO)")
    }

    private fun resizeParticleVbo(dataSize: Int) {
        if (dataSize > particleVboCapacity) {
            var newCapacity = particleVboCapacity.toDouble()
            do {
                newCapacity *= 1.5
            } while (newCapacity < dataSize)

            val finalCapacity = newCapacity.toInt().coerceAtLeast(dataSize)

            gl.glBindBuffer(GL30.GL_ARRAY_BUFFER, particleVbo)
            gl.glBufferData(GL30.GL_ARRAY_BUFFER, finalCapacity, null, GL30.GL_DYNAMIC_DRAW)
            gl.glBindBuffer(GL30.GL_ARRAY_BUFFER, 0)

            particleVboCapacity = finalCapacity
        }
    }

    private fun createQuadMesh() {
        val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        val buf = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buf.put(vertices).position(0)

        val vao = oneInt()
        gl.glGenVertexArrays(1, vao)
        quadVao = vao.get(0)

        gl.glBindVertexArray(quadVao)

        // Quad vertices (location 0)
        val vbo = oneInt()
        gl.glGenBuffers(1, vbo)
        quadVbo = vbo.get(0)

        gl.glBindBuffer(GL30.GL_ARRAY_BUFFER, quadVbo)
        gl.glBufferData(GL30.GL_ARRAY_BUFFER, vertices.size * 4, buf, GL30.GL_STATIC_DRAW)

        gl.glEnableVertexAttribArray(0)
        gl.glVertexAttribPointer(0, 2, GL30.GL_FLOAT, false, 0, 0)

        // === INSTANCE ATTRIBUTES (particle data) ===
        gl.glBindBuffer(GL30.GL_ARRAY_BUFFER, particleVbo)
        val stride = PARTICLE_STRUCT_SIZE

        // location 1: vec2 pos          offset 0
        gl.glEnableVertexAttribArray(1)
        gl.glVertexAttribPointer(1, 2, GL30.GL_FLOAT, false, stride, 0)
        gl.glVertexAttribDivisor(1, 1)

        // location 2: uint color        offset 8
        gl.glEnableVertexAttribArray(2)
        gl.glVertexAttribIPointer(2, 1, GL30.GL_UNSIGNED_INT, stride, 8)
        gl.glVertexAttribDivisor(2, 1)

        // location 3: uint packed1      offset 12
        gl.glEnableVertexAttribArray(3)
        gl.glVertexAttribIPointer(3, 1, GL30.GL_UNSIGNED_INT, stride, 12)
        gl.glVertexAttribDivisor(3, 1)

        // location 4: uint packed2      offset 16
        gl.glEnableVertexAttribArray(4)
        gl.glVertexAttribIPointer(4, 1, GL30.GL_UNSIGNED_INT, stride, 16)
        gl.glVertexAttribDivisor(4, 1)

        gl.glBindVertexArray(0)
        gl.glBindBuffer(GL30.GL_ARRAY_BUFFER, 0)

        println("✅ Quad + Instance attributes VAO создан (desktop VBO)")
    }

    private fun createSceneFbo(width: Int, height: Int) {
        // Color texture
        val tex = oneInt()
        gl.glGenTextures(1, tex)
        sceneColorTex = tex.get(0)

        gl.glBindTexture(GL30.GL_TEXTURE_2D, sceneColorTex)
        gl.glTexImage2D(
            GL30.GL_TEXTURE_2D, 0, GL30.GL_RGBA8,
            width, height, 0,
            GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE, null
        )
        gl.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MIN_FILTER, GL30.GL_LINEAR)
        gl.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAG_FILTER, GL30.GL_LINEAR)
        gl.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_S, GL30.GL_CLAMP_TO_EDGE)
        gl.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_T, GL30.GL_CLAMP_TO_EDGE)
        gl.glBindTexture(GL30.GL_TEXTURE_2D, 0)

        // Depth renderbuffer
        val rbo = oneInt()
        gl.glGenRenderbuffers(1, rbo)
        sceneDepthRbo = rbo.get(0)

        gl.glBindRenderbuffer(GL30.GL_RENDERBUFFER, sceneDepthRbo)
        gl.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH_COMPONENT24, width, height)
        gl.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0)

        // FBO
        val f = oneInt()
        gl.glGenFramebuffers(1, f)
        sceneFbo = f.get(0)

        gl.glBindFramebuffer(GL30.GL_FRAMEBUFFER, sceneFbo)
        gl.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL30.GL_TEXTURE_2D, sceneColorTex, 0
        )
        gl.glFramebufferRenderbuffer(
            GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
            GL30.GL_RENDERBUFFER, sceneDepthRbo
        )

        val status = gl.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("Scene FBO incomplete: $status")
        }
        gl.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
    }

    private fun createBlurFbo(width: Int, height: Int) {
        val tex = oneInt()
        gl.glGenTextures(1, tex)
        blurColorTex = tex.get(0)

        gl.glBindTexture(GL30.GL_TEXTURE_2D, blurColorTex)
        gl.glTexImage2D(
            GL30.GL_TEXTURE_2D, 0, GL30.GL_RGBA8,
            width, height, 0,
            GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE, null
        )
        gl.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MIN_FILTER, GL30.GL_LINEAR)
        gl.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAG_FILTER, GL30.GL_LINEAR)
        gl.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_S, GL30.GL_CLAMP_TO_EDGE)
        gl.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_T, GL30.GL_CLAMP_TO_EDGE)
        gl.glBindTexture(GL30.GL_TEXTURE_2D, 0)

        val f = oneInt()
        gl.glGenFramebuffers(1, f)
        blurFbo = f.get(0)

        gl.glBindFramebuffer(GL30.GL_FRAMEBUFFER, blurFbo)
        gl.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL30.GL_TEXTURE_2D, blurColorTex, 0
        )

        val status = gl.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("Blur FBO incomplete: $status")
        }
        gl.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
    }

    private fun createDistortFbo(width: Int, height: Int) {
        val tex = oneInt()
        gl.glGenTextures(1, tex)
        distortColorTex = tex.get(0)

        gl.glBindTexture(GL30.GL_TEXTURE_2D, distortColorTex)
        gl.glTexImage2D(
            GL30.GL_TEXTURE_2D, 0, GL30.GL_RGBA8,
            width, height, 0,
            GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE, null
        )
        gl.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MIN_FILTER, GL30.GL_LINEAR)
        gl.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAG_FILTER, GL30.GL_LINEAR)
        gl.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_S, GL30.GL_CLAMP_TO_EDGE)
        gl.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_T, GL30.GL_CLAMP_TO_EDGE)
        gl.glBindTexture(GL30.GL_TEXTURE_2D, 0)

        val f = oneInt()
        gl.glGenFramebuffers(1, f)
        distortFbo = f.get(0)

        gl.glBindFramebuffer(GL30.GL_FRAMEBUFFER, distortFbo)
        gl.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL30.GL_TEXTURE_2D, distortColorTex, 0
        )

        val status = gl.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("Distort FBO incomplete: $status")
        }
        gl.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
    }

    private fun deleteFbos() {
        if (sceneFbo != 0) {
            gl.glDeleteFramebuffers(1, oneInt(sceneFbo))
            gl.glDeleteTextures(1, oneInt(sceneColorTex))
            gl.glDeleteRenderbuffers(1, oneInt(sceneDepthRbo))
            sceneFbo = 0
            sceneColorTex = 0
            sceneDepthRbo = 0
        }
        if (blurFbo != 0) {
            gl.glDeleteFramebuffers(1, oneInt(blurFbo))
            gl.glDeleteTextures(1, oneInt(blurColorTex))
            blurFbo = 0
            blurColorTex = 0
        }
        if (distortFbo != 0) {
            gl.glDeleteFramebuffers(1, oneInt(distortFbo))
            gl.glDeleteTextures(1, oneInt(distortColorTex))
            distortFbo = 0
            distortColorTex = 0
        }
    }

    override fun create() {
        val particleVert = DesktopShaderSource.load("shaders/debug/circle_android.vert")
        val particleFrag = DesktopShaderSource.load("shaders/debug/circle.frag")
        particleProgram = createProgram(particleVert, particleFrag)

        val sobelVert = DesktopShaderSource.load("shaders/post_process/post_process.vert")
        val sobelFrag = DesktopShaderSource.load("shaders/post_process/post_process.frag")
        sobelProgram = createProgram(sobelVert, sobelFrag)

        val distortVert = DesktopShaderSource.load("shaders/blur/blur.vert")
        val distortFrag = DesktopShaderSource.load("shaders/blur/ca_distort.frag")
        distortProgram = createProgram(distortVert, distortFrag)

        val blurVert = DesktopShaderSource.load("shaders/blur/blur.vert")
        val blurFrag = DesktopShaderSource.load("shaders/blur/gaussian_blur.frag")
        blurProgram = createProgram(blurVert, blurFrag)

        particleProjLoc = gl.glGetUniformLocation(particleProgram, "u_projTrans")
        particleTextureScaleLoc = gl.glGetUniformLocation(particleProgram, "u_textureScale")
        particleColorScaleLoc = gl.glGetUniformLocation(particleProgram, "u_colorScale")
        particleTextureArrayLoc = gl.glGetUniformLocation(particleProgram, "u_textureArray")

        sobelTextureLoc = gl.glGetUniformLocation(sobelProgram, "u_texture")
        sobelResolutionLoc = gl.glGetUniformLocation(sobelProgram, "u_resolution")
        sobelZoomLoc = gl.glGetUniformLocation(sobelProgram, "u_zoom")
        vignetteEnabledLoc = gl.glGetUniformLocation(sobelProgram, "u_vignetteEnabled")

        distortTextureLoc = gl.glGetUniformLocation(distortProgram, "u_texture")
        distortResolutionLoc = gl.glGetUniformLocation(distortProgram, "u_resolution")

        blurTextureLoc = gl.glGetUniformLocation(blurProgram, "u_texture")
        blurAmountLoc = gl.glGetUniformLocation(blurProgram, "u_blurAmount")
        blurResolutionLoc = gl.glGetUniformLocation(blurProgram, "u_resolution")

        createParticleVbo()
        createQuadMesh()
        createTextureArray()

        // Use the back-buffer (pixel) size, not the logical window size: on Retina macOS the
        // framebuffer is 2x the logical window, so sizing FBOs/viewport by logical dims only fills
        // a quarter of the window. The camera projection maps to clip space (-1..1), which is
        // independent of the viewport pixel size, so this renders full-window and crisp.
        val w = Gdx.graphics.backBufferWidth.coerceAtLeast(1)
        val h = Gdx.graphics.backBufferHeight.coerceAtLeast(1)

        createSceneFbo(w, h)
        sceneFboWidth = w
        sceneFboHeight = h

        createBlurFbo(w, h)
        blurFboWidth = w
        blurFboHeight = h

        createDistortFbo(w, h)
        distortFboWidth = w
        distortFboHeight = h

        gl.glViewport(0, 0, w, h)

        println("✅ ShaderManagerDesktopVbo создан (instanced attributes, macOS 4.1)")
    }

    override fun resize(width: Int, height: Int) {
        // See create(): use pixel (back-buffer) size for FBOs/viewport, not logical.
        val safeW = Gdx.graphics.backBufferWidth.coerceAtLeast(1)
        val safeH = Gdx.graphics.backBufferHeight.coerceAtLeast(1)

        deleteFbos()

        createSceneFbo(safeW, safeH)
        sceneFboWidth = safeW
        sceneFboHeight = safeH

        createBlurFbo(safeW, safeH)
        blurFboWidth = safeW
        blurFboHeight = safeH

        createDistortFbo(safeW, safeH)
        distortFboWidth = safeW
        distortFboHeight = safeH

        gl.glViewport(0, 0, safeW, safeH)

        println("✅ FBOs resized (desktop VBO)")
    }

    override fun render(
        currentRead: ByteBuffer,
        cameraProjection: Matrix4,
        isNewFrame: Boolean,
        isClear: Boolean,
        worldX: Float,
        worldY: Float,
        blurAmount: Float,
        zoom: Float,
        vignetteEnabled: Float
    ) {
        val dataSize = currentRead.remaining()
        val numInstances = dataSize / PARTICLE_STRUCT_SIZE

        if (isNewFrame && dataSize > 0) {
            resizeParticleVbo(dataSize)

            gl.glBindBuffer(GL30.GL_ARRAY_BUFFER, particleVbo)
            gl.glBufferSubData(GL30.GL_ARRAY_BUFFER, 0, dataSize, currentRead)
            gl.glBindBuffer(GL30.GL_ARRAY_BUFFER, 0)
        }

        // ====================== РЕНДЕР ЧАСТИЦ ======================
        val targetFbo = if (usePostProcess) sceneFbo else 0
        val targetW = if (usePostProcess) sceneFboWidth else Gdx.graphics.backBufferWidth
        val targetH = if (usePostProcess) sceneFboHeight else Gdx.graphics.backBufferHeight

        gl.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFbo)
        gl.glViewport(0, 0, targetW, targetH)

        gl.glDisable(GL30.GL_BLEND)
        gl.glEnable(GL30.GL_DEPTH_TEST)
        gl.glDepthFunc(GL30.GL_LESS)
        gl.glDepthMask(true)
        gl.glClear(GL30.GL_COLOR_BUFFER_BIT or GL30.GL_DEPTH_BUFFER_BIT)

        gl.glUseProgram(particleProgram)

        gl.glUniformMatrix4fv(particleProjLoc, 1, false, cameraProjection.`val`, 0)
        gl.glUniform1f(particleTextureScaleLoc, 1.0f)
        gl.glUniform1f(particleColorScaleLoc, if (usePostProcess) 0f else 1.0f)
        gl.glUniform1i(particleTextureArrayLoc, 0)

        gl.glActiveTexture(GL30.GL_TEXTURE0)
        gl.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, textureArray)

        gl.glBindVertexArray(quadVao)
        gl.glDrawArraysInstanced(GL30.GL_TRIANGLE_STRIP, 0, 4, numInstances)
        gl.glBindVertexArray(0)

        gl.glUseProgram(0)

        if (!usePostProcess) return

        // ====================== POST-PROCESS ======================
        // 1. Sobel → blurFbo
        gl.glBindFramebuffer(GL30.GL_FRAMEBUFFER, blurFbo)
        gl.glViewport(0, 0, blurFboWidth, blurFboHeight)

        gl.glDisable(GL30.GL_DEPTH_TEST)
        gl.glDisable(GL30.GL_BLEND)

        gl.glUseProgram(sobelProgram)
        gl.glUniform1i(sobelTextureLoc, 0)
        gl.glUniform2f(sobelResolutionLoc, sceneFboWidth.toFloat(), sceneFboHeight.toFloat())
        val zoomX10 = zoom * 10f
        val sobel = zoomX10.coerceIn(0.16f, 0.24f)
        gl.glUniform1f(sobelZoomLoc, sobel)
        gl.glUniform1f(vignetteEnabledLoc, vignetteEnabled)

        gl.glActiveTexture(GL30.GL_TEXTURE0)
        gl.glBindTexture(GL30.GL_TEXTURE_2D, sceneColorTex)

        gl.glBindVertexArray(quadVao)
        gl.glDrawArrays(GL30.GL_TRIANGLE_STRIP, 0, 4)
        gl.glBindVertexArray(0)

        // 2. Distort (CA) → distortFbo
        gl.glBindFramebuffer(GL30.GL_FRAMEBUFFER, distortFbo)
        gl.glViewport(0, 0, distortFboWidth, distortFboHeight)

        gl.glUseProgram(distortProgram)
        gl.glUniform1i(distortTextureLoc, 0)
        gl.glUniform2f(distortResolutionLoc, blurFboWidth.toFloat(), blurFboHeight.toFloat())

        gl.glActiveTexture(GL30.GL_TEXTURE0)
        gl.glBindTexture(GL30.GL_TEXTURE_2D, blurColorTex)

        gl.glBindVertexArray(quadVao)
        gl.glDrawArrays(GL30.GL_TRIANGLE_STRIP, 0, 4)
        gl.glBindVertexArray(0)

        // 3. Gaussian Blur → экран
        gl.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
        gl.glViewport(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)

        gl.glUseProgram(blurProgram)
        gl.glUniform1i(blurTextureLoc, 0)
        gl.glUniform1f(blurAmountLoc, (blurAmount + 0.04f) * 0.5f)
        gl.glUniform2f(blurResolutionLoc, blurFboWidth.toFloat(), blurFboHeight.toFloat())

        gl.glActiveTexture(GL30.GL_TEXTURE0)
        gl.glBindTexture(GL30.GL_TEXTURE_2D, distortColorTex)

        gl.glBindVertexArray(quadVao)
        gl.glDrawArrays(GL30.GL_TRIANGLE_STRIP, 0, 4)
        gl.glBindVertexArray(0)

        gl.glUseProgram(0)
    }

    override fun dispose() {
        gl.glDeleteProgram(particleProgram)
        gl.glDeleteProgram(sobelProgram)
        gl.glDeleteProgram(distortProgram)
        gl.glDeleteProgram(blurProgram)

        val one = BufferUtils.newIntBuffer(1)
        one.put(quadVao); one.flip()
        gl.glDeleteVertexArrays(1, one)
        gl.glDeleteBuffers(1, oneInt(quadVbo))
        gl.glDeleteBuffers(1, oneInt(particleVbo))

        if (textureArray != 0) {
            gl.glDeleteTextures(1, oneInt(textureArray))
        }

        deleteFbos()

        println("✅ ShaderManagerDesktopVbo disposed")
    }
}
