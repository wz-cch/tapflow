package com.tapflow.android.data

import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Shared JSON configuration.
 *
 * The class discriminator must be "type". It cannot be "kind", which would collide with the
 * GlobalStep.kind and GlobalNode.kind properties; kotlinx.serialization throws at runtime on
 * such a collision rather than failing to compile.
 */
val AppJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

fun newId(): String = UUID.randomUUID().toString()
