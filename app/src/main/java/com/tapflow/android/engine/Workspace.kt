package com.tapflow.android.engine

import com.tapflow.android.data.Clip
import com.tapflow.android.data.DocFile
import com.tapflow.android.data.LoadedClip
import com.tapflow.android.data.Repo
import com.tapflow.android.data.ScreenSpec
import com.tapflow.android.data.Step
import com.tapflow.android.data.WorkspaceSnapshot
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The set of actions the user is currently working on — what the markers on screen represent.
 *
 * Recording appends here rather than saving straight to a clip, so a freshly recorded sequence can
 * be nudged around before being committed. Pressing save is what turns it into a [Clip].
 *
 * Every mutation is written to a draft file, so the work survives the accessibility service being
 * torn down and reconnected.
 */
object Workspace {

    val steps = MutableStateFlow<List<Step>>(emptyList())

    /** True when the workspace differs from the clip it came from (or from nothing). */
    val dirty = MutableStateFlow(false)

    /**
     * The file this workspace was opened from. `💾` overwrites it; save-as writes a new one.
     *
     * Null for a recording that has never been saved, and that is exactly the case where `💾` has nothing to
     * overwrite and has to ask where the file should go. A [MutableStateFlow] rather than a plain field
     * because two screens show which file is open — the home screen's "open" badge and the toolbar — and
     * neither of them is in a position to poll.
     */
    val source = MutableStateFlow<DocFile?>(null)

    /** Screen geometry the steps were captured on. Null until the first step is recorded. */
    var screen: ScreenSpec? = null
        private set

    val isEmpty: Boolean get() = steps.value.isEmpty()
    val size: Int get() = steps.value.size

    /**
     * Brings back the draft if it held unsaved work, and says whether it did.
     *
     * A saved workspace is already a clip, so restoring it looks like the app opening a file by
     * itself. The draft is here to stop a long recording being lost, nothing more.
     *
     * Returning true is the caller's cue to ask whether the recovery was wanted. It can be asked
     * *after* the fact, rather than before restoring, precisely because restoring is the safe direction:
     * the steps are in memory either way, so an unanswered question loses nothing, and the alternative —
     * leaving them on disk until answered — races the first thing that writes the draft.
     */
    fun restore(): Boolean {
        val snapshot = Repo.readWorkspace()
        if (!snapshot.dirty) {
            clear()
            return false
        }
        steps.value = snapshot.steps
        source.value = snapshot.sourceRef?.let { DocFile(it, snapshot.sourceName.orEmpty()) }
        screen = snapshot.screen
        dirty.value = true
        return true
    }

    fun append(step: Step, capturedOn: ScreenSpec) {
        if (screen == null) screen = capturedOn
        snapshot()
        steps.value = steps.value + step
        markDirty()
    }

    /**
     * Previous states, newest last. Undo pops one.
     *
     * Cheap enough to keep a real history rather than the tail-drop this used to be: [steps] holds an
     * immutable list, so a snapshot is one reference and not a copy. The tail-drop version could only
     * undo the most recent recording; it could not undo a drag, a delete, or an insert in the middle,
     * which left editing with no way back at all.
     */
    private val history = ArrayDeque<List<Step>>()

    /**
     * True while a single user gesture is producing a run of updates.
     *
     * A drag calls [updateStep] on every touch sample. Snapshotting each one would fill the history
     * with intermediate positions and make undo useless, so the run collapses to the one state it
     * started from.
     */
    private var coalescing = false

    val canUndo: Boolean get() = history.isNotEmpty()

    /** Steps back one edit. Returns false when there is nothing left to undo. */
    fun undo(): Boolean {
        val previous = history.removeLastOrNull() ?: return false
        steps.value = previous
        coalescing = false
        markDirty()
        return true
    }

    private fun snapshot() {
        history.addLast(steps.value)
        while (history.size > HISTORY_LIMIT) history.removeFirst()
    }

    fun stepById(id: String?): Step? = id?.let { key -> steps.value.firstOrNull { it.id == key } }

    /**
     * Replaces a step in place.
     *
     * [persist] is false while a marker is being dragged: writing the draft file on every touch
     * sample would be dozens of writes per gesture. [flush] commits once the drag ends.
     */
    fun updateStep(step: Step, persist: Boolean = true) {
        val current = steps.value
        if (current.none { it.id == step.id }) return
        // One snapshot per gesture, not per sample: the first update of a drag records where it began
        // and the rest fold into it. flush() closes the run.
        if (!coalescing) snapshot()
        coalescing = !persist
        steps.value = current.map { if (it.id == step.id) step else it }
        dirty.value = true
        if (persist) persist()
    }

    fun removeStep(id: String) {
        val current = steps.value
        if (current.none { it.id == id }) return
        snapshot()
        steps.value = current.filterNot { it.id == id }
        markDirty()
    }

    /**
     * Inserts immediately **after** [afterId], or at the end when it is null or unknown.
     *
     * The only insertion direction there is, because it is the direction recording grows in: every step
     * you have ever added to a script landed after the previous one. Insert-before is the more expressive
     * primitive, but having one insertion carry a direction while its three siblings did not made that
     * one read as an exception — so direction moved to [moveStep], where a button cannot exist without
     * one, and reaching the front became insert-then-move.
     *
     * Recording never has a selection, so recorded steps keep landing at the end without a special case.
     */
    fun insertAfter(afterId: String?, step: Step, capturedOn: ScreenSpec) {
        if (screen == null) screen = capturedOn
        snapshot()
        val current = steps.value
        val index = current.indexOfFirst { it.id == afterId }
        steps.value = if (index < 0) {
            current + step
        } else {
            current.toMutableList().apply { add(index + 1, step) }
        }
        markDirty()
    }

    // There is deliberately no insertBefore. Everything inserts after, and reaching the front is
    // insert-then-move — see [moveStep]. Having one insertion carry a direction while its three
    // siblings did not made that one read as an exception rather than a rule.

    /**
     * Moves a step [delta] slots. Returns false when it is already at that end.
     *
     * Direction belongs here rather than on the insertion calls: a move button cannot exist without a
     * direction, whereas an insert button carrying one is an exception to an otherwise single rule.
     *
     * A step is one whole thing, so this reorders the list and nothing else — which means [Step.delayBefore]
     * travels with it for free. That is the point, not an accident: the delay belongs to the step, not to
     * the slot, so moving a step must not silently retime it. The rhythm around it does change, but that
     * is what moving a step means, and the delay stays separately editable.
     *
     * Snapshots like every other mutation, so undo covers a move.
     */
    fun moveStep(id: String, delta: Int): Boolean {
        val current = steps.value
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return false
        val target = index + delta
        if (target !in current.indices) return false

        snapshot()
        steps.value = current.toMutableList().apply { add(target, removeAt(index)) }
        markDirty()
        return true
    }

    /** Writes the draft after a run of non-persisting edits, and ends the coalescing window. */
    fun flush() {
        coalescing = false
        persist()
    }

    fun clear() {
        // Undoing across a load or a clear would resurrect steps from a different clip, which reads as
        // the app inventing content. Both are deliberate boundaries, so history stops at them.
        history.clear()
        coalescing = false
        steps.value = emptyList()
        source.value = null
        screen = null
        dirty.value = false
        persist()
    }

    /**
     * Takes a working copy of a clip that was just read off disk.
     *
     * "Working copy" is the whole of it: the steps are in memory from here on and the file is not held open,
     * so editing them changes nothing on disk until a save, and a change made to the file from elsewhere does
     * not appear here. Same as any editor with a document open.
     */
    fun load(loaded: LoadedClip) {
        history.clear()
        coalescing = false
        steps.value = loaded.clip.steps
        source.value = loaded.file
        screen = loaded.clip.screen
        dirty.value = false
        persist()
    }

    /**
     * What a save attempt did.
     *
     * Three outcomes, not two, and keeping [Failed] distinct from [Nothing] is the point. They used to
     * share a null return, so the day the folder became unreachable the message said "nothing to
     * save" — which is not merely unhelpful, it is the opposite of what happened.
     */
    sealed interface Saved {
        data class Ok(val file: DocFile) : Saved

        /** Empty workspace, or no screen geometry ever captured. Nothing was attempted. */
        data object Nothing : Saved

        /** The file refused the write. The draft stays dirty, so recovery still holds the work. */
        data object Failed : Saved
    }

    /**
     * Writes the workspace to [target], which becomes the file it is open from.
     *
     * One entry point for both saves. Overwriting is `commit(source.value!!)` and saving as is
     * `commit(whatever the picker returned)` — there is no `asNew` flag any more, because there is nothing
     * for it to decide: the target file is chosen before this is called, by whoever asked for the save. That
     * also retires the "save as new leaves the flow pointing at the original" trap, since a flow now
     * references a *location*, and saving to a different location is visibly a different file.
     *
     * **`dirty` is only cleared once the write has landed.** That is what makes a failed save safe rather
     * than silent: the workspace stays unsaved, so the draft survives on disk and the unsaved-work recovery
     * covers it.
     *
     * **Writes a file, so never call this on the main thread.** On API 29+ that write is a ContentProvider
     * round trip, and a slow provider showed up on a device as the whole UI locking up rather than as a save
     * taking a moment.
     */
    fun commit(target: DocFile): Saved {
        val currentSteps = steps.value
        val capturedOn = screen
        if (currentSteps.isEmpty() || capturedOn == null) return Saved.Nothing

        if (!Repo.saveClip(target.ref, Clip(steps = currentSteps, screen = capturedOn), target.name)) {
            return Saved.Failed
        }

        source.value = target
        dirty.value = false
        persist()
        return Saved.Ok(target)
    }

    private fun markDirty() {
        dirty.value = true
        persist()
    }

    private fun persist() = Repo.writeWorkspace(
        WorkspaceSnapshot(
            steps = steps.value,
            sourceRef = source.value?.ref,
            sourceName = source.value?.name,
            screen = screen,
            dirty = dirty.value,
        )
    )

    /** Deep enough for any editing session; the entries are references, so the cost is negligible. */
    private const val HISTORY_LIMIT = 60
}
