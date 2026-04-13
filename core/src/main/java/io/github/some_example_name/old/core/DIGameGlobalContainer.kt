package io.github.some_example_name.old.core

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.I18NBundle
import com.badlogic.gdx.utils.Json
import io.github.some_example_name.old.systems.genomics.genome.GenomeJsonReader
import io.github.some_example_name.old.systems.render.ShaderManager
import io.github.some_example_name.old.systems.render.ShaderManagerLibgdxApi
import java.util.Locale

object DIGameGlobalContainer {

    lateinit var fileProvider: FileProvider
    val json by lazy { Json() }
    val bundle: I18NBundle by lazy {
        I18NBundle.createBundle(
            Gdx.files.internal("ui/i18n/MyBundle"),
            Locale.getDefault()
        )
    }

    val genomeJsonReader = GenomeJsonReader()

    val shaderManager: ShaderManager = when (Gdx.app.type) {
        Application.ApplicationType.Desktop -> ShaderManagerLibgdxApi()
        Application.ApplicationType.Android -> TODO()
        Application.ApplicationType.HeadlessDesktop -> TODO()
        Application.ApplicationType.Applet -> TODO()
        Application.ApplicationType.WebGL -> TODO()
        Application.ApplicationType.iOS -> TODO()
    }
}
