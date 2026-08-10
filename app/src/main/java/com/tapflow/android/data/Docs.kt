package com.tapflow.android.data

/**
 * What a file is, told by its extension.
 *
 * A clip and a flow are both text files, and this is the only thing that distinguishes them from the
 * outside. Folders carry no meaning of their own — they are how the user organises their own work, not
 * something the app reads anything into — so the name is where the kind has to live.
 *
 * **It decides what is listed.** The browser shows only the kind being asked for, which is why picking the
 * wrong one is not a thing that can happen any more. What a file *is*, though, is still decided by parsing
 * it: a `.clip` full of something else fails to open, and says so.
 */
enum class DocKind(val extension: String) {
    CLIP(".clip"),
    FLOW(".flow"),
    ;

    /**
     * Whether [fileName] looks like one of these. One spelling, and only one.
     *
     * `<name>.clip.json` used to be accepted as well, because the platform picker created the file and a
     * document provider may append an extension derived from the MIME type — leaving a file we had written
     * but would not offer to reopen, the worst outcome available. Nothing appends anything now: the browser
     * composes the name before the file exists, asks for exactly that, and checks what it got. So the second
     * spelling has no source, and keeping it would only mean listing files this app can no longer produce.
     */
    fun matches(fileName: String): Boolean = fileName.endsWith(extension, ignoreCase = true)

    companion object {
        /** Which kind [fileName] looks like, or null when it is neither. */
        fun of(fileName: String): DocKind? = entries.firstOrNull { it.matches(fileName) }
    }
}

/**
 * The file name to show a person: no path, no extension.
 *
 * The name of a clip **is** its file name — there is no name stored inside the JSON, and that is the point.
 * Two places holding one name is two places for it to disagree, and the file name is the one the user can
 * change from outside the app, which they are entitled to do.
 */
fun displayName(fileName: String): String {
    val kind = DocKind.of(fileName) ?: return fileName
    val cut = fileName.lowercase().lastIndexOf(kind.extension)
    return if (cut <= 0) fileName else fileName.substring(0, cut)
}

/**
 * A name made safe to be a file name, with the extension that says what it is.
 *
 * Only ever a *suggestion*: on API 29 and up the document picker takes it as the pre-filled name and the
 * user may type anything, and the provider appends a suffix of its own when the name is taken. Collisions
 * are therefore not handled here — two clips may legitimately share a name, since neither is found by it.
 */
fun suggestedFileName(name: String, kind: DocKind): String {
    val cleaned = name
        .map { if (it in ILLEGAL_IN_FILE_NAME || it.isISOControl()) ' ' else it }
        .joinToString("")
        .trim()
        .take(MAX_FILE_NAME)
        .trim()
    // Anything already ending in the extension comes off first. The name field asks for a name and never
    // shows one, but a person who types `Login.clip` anyway means the same file as one who types `Login` —
    // and appending unconditionally turned that into `Login.clip.clip`, whose display name was `Login.clip`.
    // That is where the extension appeared to come back on its own.
    return displayName(cleaned).ifEmpty { FALLBACK_FILE_NAME } + kind.extension
}

/** Reserved on FAT and exFAT as well as ext4, since an SD card is a normal place to keep these. */
private const val ILLEGAL_IN_FILE_NAME = "/\\:*?\"<>|"

/** Well under the 255-byte limit, which CJK names reach three times faster than Latin ones. */
private const val MAX_FILE_NAME = 60

private const val FALLBACK_FILE_NAME = "untitled"
