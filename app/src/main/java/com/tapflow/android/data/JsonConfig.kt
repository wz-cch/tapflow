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
    // Off, and measured rather than guessed. Pretty-printing a clip costs 5.3x, because a recorded
    // swipe is ~60 sampled points and each one becomes five deeply indented lines: 20 clips of 100
    // steps came to 5.5 MB pretty against 1.0 MB compact. That whole file used to be rewritten on
    // every save, and saves now go through a ContentProvider to a folder the user chose, so the
    // multiplier lands directly on how long pressing save takes.
    //
    // Nothing is lost. Nobody hand-reads 250 KB of coordinate samples — the readability that
    // matters is the *file name*, which is why saved clips are named after the clip. A parser does
    // not care about whitespace.
    prettyPrint = false
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

fun newId(): String = UUID.randomUUID().toString()
