package com.tapflow.android.data

/**
 * What a file is, told by its extension.
 *
 * A clip and a flow are both text files, and this is the only thing that distinguishes them from the
 * outside. Nothing about *where* a file sits carries meaning — there are no folders in this app's model,
 * only files the user picked — so the name is where the kind has to live.
 *
 * Reading does not trust it, though. [DocKind.of] decides which picker filter and which label to use, but a
 * file is accepted or rejected by whether it *parses* as the kind that was asked for: a clip copied to
 * `notes.txt` is still a clip, and a `.clip` full of something else is not one.
 */
enum class DocKind(val extension: String) {
    CLIP(".clip"),
    FLOW(".flow"),
    ;

    /**
     * Whether [fileName] looks like one of these.
     *
     * `<name>.clip.json` is accepted as well as `<name>.clip` because a document provider may append an
     * extension of its own when creating a file — `ExternalStorageProvider` derives one from the MIME type —
     * and a file we wrote but then would not offer to reopen is the worst outcome available.
     */
    fun matches(fileName: String): Boolean =
        fileName.endsWith(extension, ignoreCase = true) ||
            fileName.endsWith("$extension.json", ignoreCase = true)

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
    return (cleaned.ifEmpty { FALLBACK_FILE_NAME }) + kind.extension
}

/** Reserved on FAT and exFAT as well as ext4, since an SD card is a normal place to keep these. */
private const val ILLEGAL_IN_FILE_NAME = "/\\:*?\"<>|"

/** Well under the 255-byte limit, which CJK names reach three times faster than Latin ones. */
private const val MAX_FILE_NAME = 60

private const val FALLBACK_FILE_NAME = "untitled"
