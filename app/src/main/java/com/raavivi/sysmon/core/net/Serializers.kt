package com.raavivi.sysmon.core.net

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Decodes a boolean that the backend may emit as a JSON integer.
 *
 * The Model Log stores `cold_start` / `stream` as SQLite INTEGER (`1`/`0`) and
 * the read + `/ws/models` paths emit them as raw ints — strict kotlinx decoding
 * into a [Boolean] would throw "Unexpected JSON token … at offset N". This
 * accepts `true`/`false`, `1`/`0`, and the string forms `"1"`/`"true"`.
 */
object FlexibleBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleBoolean", PrimitiveKind.BOOLEAN)

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)

    override fun deserialize(decoder: Decoder): Boolean {
        val json = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
        val prim = json.decodeJsonElement() as? JsonPrimitive ?: return false
        prim.booleanOrNull?.let { return it }
        prim.intOrNull?.let { return it != 0 }
        return prim.content == "1" || prim.content.equals("true", ignoreCase = true)
    }
}
