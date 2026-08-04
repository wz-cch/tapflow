package com.tapflow.android.data

/**
 * A file, and the name to show for it.
 *
 * The pairing exists because the two come from different places at different costs: the ref is handed over
 * by whatever picked the file, and the name has to be asked of the file system. Carrying them together means
 * nothing downstream has to do IO to label a row.
 */
data class DocFile(val ref: String, val name: String)

/** One clip read off disk, with the file it came from. */
class LoadedClip(val file: DocFile, val clip: Clip)

/**
 * A flow read into memory, with each clip it references resolved once.
 *
 * **This is a snapshot, and that is deliberate.** Opening a flow reads its clips; editing one of those clips
 * afterwards does not change the copy held here, and running the flow again does not pick the edit up until
 * the flow is reopened. Which is how a text editor behaves with two windows on one file, and the alternative
 * — re-reading every clip before every run — would put N provider round trips in front of the play button
 * and still not be live.
 *
 * A clip that could not be read is simply absent from [resolved]. There is no third state: either the file
 * opened, or the row shows `!` and offers to be pointed somewhere else. No matching by name, no matching by
 * id, no guessing which of two similar files was meant.
 */
class OpenFlow(
    val file: DocFile,
    val flow: Flow,
    private val resolved: Map<String, LoadedClip>,
) {

    /** The clip at [index] in the flow, or null when its file could not be read. */
    fun clipAt(index: Int): LoadedClip? = flow.clips.getOrNull(index)?.let { resolved[it.ref] }

    /**
     * What to call the clip at [index].
     *
     * The file's current name when it could be read, so a clip renamed outside the app reads correctly the
     * next time the flow is opened — the stored one is a fallback for exactly the case where there is no
     * file to ask.
     */
    fun nameAt(index: Int): String {
        val node = flow.clips.getOrNull(index) ?: return ""
        return resolved[node.ref]?.file?.name ?: node.name
    }

    /** By ref, for expanding the flow into steps. */
    val clips: Map<String, Clip> get() = resolved.mapValues { it.value.clip }

    /** How many rows have no file behind them. Non-zero refuses a run — see FlowPlan. */
    val missingCount: Int get() = flow.clips.count { it.ref !in resolved }

    /** The same flow with a changed node list, keeping every clip already read. */
    fun withNodes(nodes: List<ClipNode>): OpenFlow =
        OpenFlow(file, flow.copy(clips = nodes), resolved)

    /** The same flow with one more clip read in, for the `+` button and for repointing a broken row. */
    fun withNodes(nodes: List<ClipNode>, added: LoadedClip): OpenFlow =
        OpenFlow(file, flow.copy(clips = nodes), resolved + (added.file.ref to added))

    fun withFlow(updated: Flow): OpenFlow = OpenFlow(file, updated, resolved)

    /** The same flow, now living at a different file. Its clips were read already and have not moved. */
    fun movedTo(moved: DocFile): OpenFlow = OpenFlow(moved, flow, resolved)
}
