package io.github.some_example_name.attempts.game.sound

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Sound

class SoundManager {
    private val pikSounds: List<Sound> = listOf<Sound>(
        Gdx.audio.newSound(Gdx.files.internal("pik1.mp3")),
        Gdx.audio.newSound(Gdx.files.internal("pik2.mp3")),
        Gdx.audio.newSound(Gdx.files.internal("pik3.mp3")),
        Gdx.audio.newSound(Gdx.files.internal("pik4.mp3")),
        Gdx.audio.newSound(Gdx.files.internal("pik5.mp3"))
    )

    fun playPik() {
        pikSounds.random().play()
    }
}
