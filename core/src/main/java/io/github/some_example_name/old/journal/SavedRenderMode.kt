package io.github.some_example_name.old.journal

import io.github.some_example_name.render.RenderMode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Номера и имена режимов уже записаны в журналах игры. */
@Serializable
private enum class RecordedMode { REALISTIC, DEBUG, NOSTALGIC, ULTRA_REALISTIC, TISSUE_MAP }

object SavedRenderMode : KSerializer<RenderMode> {
    private val codec = RecordedMode.serializer()
    override val descriptor = codec.descriptor
    override fun serialize(encoder: Encoder, value: RenderMode) =
        codec.serialize(encoder, RecordedMode.valueOf(value.name))
    override fun deserialize(decoder: Decoder): RenderMode {
        val old = codec.deserialize(decoder)
        return when (old) {
            RecordedMode.REALISTIC -> RenderMode.DEBUG
            RecordedMode.ULTRA_REALISTIC -> RenderMode.TISSUE_MAP
            else -> RenderMode.valueOf(old.name)
        }
    }
}
