package com.tapflow.android.engine

import com.tapflow.android.data.Clip
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

    /** Clip this workspace was loaded from. Save overwrites it; save-as always creates a new one. */
    var sourceClipId: String? = null
        private set

    /** Screen geometry the steps were captured on. Null until the first step is recorded. */
    var screen: ScreenSpec? = null
        private set

    val isEmpty: Boolean get() = steps.value.isEmpty()
    val size: Int get() = steps.value.size

    /**
     * Brings back the draft, but only if it held unsaved work.
     *
     * A saved workspace is already a clip, so restoring it looks like the app opening a file by
     * itself. The draft is here to stop a long recording being lost, nothing more.
     */
    fun restore() {
        val snapshot = Repo.readWorkspace()
        if (!snapshot.dirty) {
            clear()
            return
        }
        steps.value = snapshot.steps
        sourceClipId = snapshot.sourceClipId
        screen = snapshot.screen
        dirty.value = true
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
     * This is the direction the toolbar inserts in, because it is the direction recording grows in:
     * every step you have ever added to a script landed after the previous one. Offering only
     * insert-before there read backwards even though it is the more expressive primitive — and the
     * expressiveness is not lost, because [insertBefore] is offered by name on the step settings panel,
     * where a labelled button says which way it goes.
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

    /**
     * Inserts immediately **before** [beforeId], or at the end when it is null or unknown.
     *
     * The only way to express "make this the first step", which is why it survives alongside
     * [insertAfter] rather than being replaced by it.
     */
    fun insertBefore(beforeId: String?, step: Step, capturedOn: ScreenSpec) {
        if (screen == null) screen = capturedOn
        snapshot()
        val current = steps.value
        val index = current.indexOfFirst { it.id == beforeId }
        steps.value = if (index < 0) current + step else current.toMutableList().apply { add(index, step) }
        markDirty()
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
        sourceClipId = null
        screen = null
        dirty.value = false
        persist()
    }

    fun load(clip: Clip) {
        history.clear()
        coalescing = false
        steps.value = clip.steps
        sourceClipId = clip.id
        screen = clip.screen
        dirty.value = false
        persist()
    }

    /**
     * Commits the workspace to a clip under [name].
     *
     * When [asNew] is false and the workspace came from a clip, that clip is updated in place;
     * otherwise a new one is created. [name] always wins, so renaming while saving works.
     *
     * Returns null when there is nothing to save, or when no screen geometry was ever captured —
     * without it replay could not rescale coordinates on another device.
     */
    fun commit(name: String, now: Long, asNew: Boolean): Clip? {
        val currentSteps = steps.value
        val capturedOn = screen
        if (currentSteps.isEmpty() || capturedOn == null) return null

        val cleanName = name.trim().ifEmpty { return null }
        val existing = if (asNew) null else Repo.clipById(sourceClipId)
        val clip = existing?.copy(
            name = cleanName,
            steps = currentSteps,
            screen = capturedOn,
            updatedAt = now,
        ) ?: Clip(name = cleanName, steps = currentSteps, screen = capturedOn, createdAt = now)

        Repo.upsertClip(clip)
        sourceClipId = clip.id
        Repo.setCurrentClip(clip.id)
        dirty.value = false
        persist()
        return clip
    }

    private fun markDirty() {
        dirty.value = true
        persist()
    }

    private fun persist() =
        Repo.writeWorkspace(WorkspaceSnapshot(steps.value, sourceClipId, screen, dirty.value))

    /** Deep enough for any editing session; the entries are references, so the cost is negligible. */
    private const val HISTORY_LIMIT = 60
}
