package io.github.some_example_name.old.systems.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Matrix4
import io.github.some_example_name.old.systems.render.components.BlurPostProcessRenderer
import io.github.some_example_name.old.systems.render.components.DistortRenderer
import io.github.some_example_name.old.systems.render.components.ParticleRenderer
import io.github.some_example_name.old.systems.render.components.PheromoneRenderer
import io.github.some_example_name.old.systems.render.components.PostProcessRenderer
import io.github.some_example_name.old.systems.render.components.RenderComponent
import io.github.some_example_name.old.systems.render.components.RenderContext
import io.github.some_example_name.old.systems.render.components.VignetteRenderer
import java.nio.ByteBuffer


var doesUsePostProcess = true

/**
 * Orchestrates the render pipeline using composable render components.
 *
 * The rendering pipeline is executed in order:
 * 1. ParticleRenderer - renders particles to scene FBO
 * 2. PostProcessRenderer - applies sobel edge detection + pastel stylization
 * 3. DistortRenderer - applies chromatic aberration
 * 4. BlurPostProcessRenderer - applies gaussian blur
 * 5. PheromoneRenderer - renders pheromones
 * 6. VignetteRenderer - applies vignette (final pass, renders to screen)
 *
 * New components can be added by implementing RenderComponent and
 * inserting into the components list at the desired position.
 */
class ShaderManager(val texturePaths: List<String>) {

    // Shared resources
    private lateinit var mesh: Mesh
    private lateinit var fbo: FrameBuffer
    private lateinit var blurFbo: FrameBuffer
    private lateinit var distortFbo: FrameBuffer

    // Render context shared between components
    private val renderContext = RenderContext()

    // Ordered list of render components - easily reorderable
    private val components = mutableListOf<RenderComponent>()

    fun create() {
        createFullscreenMesh()
        createFBOs()

        // Initialize render components in order
        // To change rendering order, simply reorder this list
        components.add(ParticleRenderer(texturePaths))
        components.add(PostProcessRenderer())
        components.add(DistortRenderer())
        components.add(BlurPostProcessRenderer())
        components.add(PheromoneRenderer())
        components.add(VignetteRenderer())

        // Create all components
        components.forEach { it.create() }
    }

    private fun createFullscreenMesh() {
        val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val attributes = VertexAttributes(
            VertexAttribute(
                VertexAttributes.Usage.Position,
                2,
                ShaderProgram.POSITION_ATTRIBUTE
            )
        )
        mesh = Mesh(false, 4, 0, attributes).apply { setVertices(vertices) }
    }

    private fun createFBOs() {
        val width = Gdx.graphics.width.coerceAtLeast(1)
        val height = Gdx.graphics.height.coerceAtLeast(1)

        fbo = FrameBuffer(Pixmap.Format.RGBA8888, width, height, true)
        println("✅ FBO создан: ${width}×${height} (для пост-процессинга)")

        blurFbo = FrameBuffer(Pixmap.Format.RGBA8888, width, height, false)
        blurFbo.colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        fbo.colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)

        distortFbo = FrameBuffer(Pixmap.Format.RGBA8888, width, height, false)
        distortFbo.colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
    }

    fun resize(width: Int, height: Int) {
        val safeW = width.coerceAtLeast(1)
        val safeH = height.coerceAtLeast(1)

        if (::fbo.isInitialized) fbo.dispose()
        fbo = FrameBuffer(Pixmap.Format.RGBA8888, safeW, safeH, true)

        if (::blurFbo.isInitialized) blurFbo.dispose()
        blurFbo = FrameBuffer(Pixmap.Format.RGBA8888, safeW, safeH, false)

        if (::distortFbo.isInitialized) distortFbo.dispose()
        distortFbo = FrameBuffer(Pixmap.Format.RGBA8888, safeW, safeH, false)

        // Linear filtering for smooth sampling
        fbo.colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        blurFbo.colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        distortFbo.colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)

        println("✅ FBOs resized → scene: ${width}×${height}, blur: ${safeW}×${safeH}")

        // Notify all components of resize
        components.forEach { it.resize(width, height) }
    }

    /**
     * @param cameraProjection full camera.combined (kept for any world-space debug)
     * @param cameraRelativeProjection camera.projection — maps camera-local coords to clip
     * @param worldX camera world X (origin used when packing particle positions)
     * @param worldY camera world Y
     *
     * Particle/pheromone buffers must store positions as (world - camera) so float
     * precision stays high near the viewport (no stair-step motion at large coords).
     */
    fun render(
        currentRead: ByteBuffer,
        cameraProjection: Matrix4,
        cameraRelativeProjection: Matrix4,
        isNewFrame: Boolean,
        isClear: Boolean,
        worldX: Float,
        worldY: Float,
        blurAmount: Float,
        zoom: Float,
        vignetteEnabled: Float,
        pheromoneData: ByteBuffer? = null
    ) {
        val dataSize = currentRead.remaining()
        val numInstances = dataSize / RenderSystem.PARTICLE_STRUCT_SIZE

        // Update render context with current frame data
        renderContext.apply {
            fullscreenMesh = mesh
            this.cameraProjection = cameraProjection
            this.cameraRelativeProjection = cameraRelativeProjection
            this.cameraX = worldX
            this.cameraY = worldY
            particleData = currentRead
            this.numInstances = numInstances
            this.isNewFrame = isNewFrame
            this.blurAmount = blurAmount
            this.zoom = zoom
            this.vignetteEnabled = vignetteEnabled
            this.usePostProcess = doesUsePostProcess
            this.pheromoneData = pheromoneData
            this.numPheromoneInstances = pheromoneData?.let { it.remaining() / RenderSystem.PHEROMONE_STRUCT_SIZE } ?: 0

            // Set FBO references for component communication
            sceneFbo = fbo
            this.blurFbo = this@ShaderManager.blurFbo
            this.distortFbo = this@ShaderManager.distortFbo
            currentTexture = null
        }

        // Execute render pipeline - each component in order
        components.forEach { component ->
            component.render(renderContext)
        }

        Gdx.gl.glUseProgram(0)
    }

    fun dispose() {
        // Dispose all components
        components.forEach { it.dispose() }
        components.clear()

        mesh.dispose()

        if (::fbo.isInitialized) fbo.dispose()
        if (::blurFbo.isInitialized) blurFbo.dispose()
        if (::distortFbo.isInitialized) distortFbo.dispose()
    }

    // === Public API for component management ===

    /**
     * Add a render component at the end of the pipeline.
     */
    fun addComponent(component: RenderComponent) {
        component.create()
        components.add(component)
    }

    /**
     * Insert a render component at a specific position in the pipeline.
     * @param index Position in the pipeline (0 = first)
     */
    fun insertComponent(index: Int, component: RenderComponent) {
        component.create()
        components.add(index, component)
    }

    /**
     * Remove a render component from the pipeline.
     */
    fun removeComponent(component: RenderComponent) {
        if (components.remove(component)) {
            component.dispose()
        }
    }

    /**
     * Get the current number of components in the pipeline.
     */
    fun getComponentCount(): Int = components.size
}
