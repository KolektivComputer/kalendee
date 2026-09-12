package dev.kolektiv.kalendee.calendar

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = OptionalFieldSerializer::class)
sealed class OptionalField<out T> {
    data object Absent : OptionalField<Nothing>()
    data class Present<out T>(val value: T) : OptionalField<T>()
}

fun <T> OptionalField<T>.getOrElse(existing: T): T = when (this) {
    OptionalField.Absent -> existing
    is OptionalField.Present -> value
}

class OptionalFieldSerializer<T>(
    private val dataSerializer: KSerializer<T>,
) : KSerializer<OptionalField<T>> {
    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        SerialDescriptor("dev.kolektiv.kalendee.calendar.OptionalField", dataSerializer.descriptor)

    override fun serialize(encoder: Encoder, value: OptionalField<T>) {
        when (value) {
            OptionalField.Absent -> error("OptionalField.Absent must be omitted from serialization")
            is OptionalField.Present -> encoder.encodeSerializableValue(dataSerializer, value.value)
        }
    }

    override fun deserialize(decoder: Decoder): OptionalField<T> =
        OptionalField.Present(decoder.decodeSerializableValue(dataSerializer))
}
