package com.tapflow.android.engine

import com.tapflow.android.data.Clip
import com.tapflow.android.data.PauseStep
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
        steps.value = steps.value + step
        markDirty()
    }

    fun appendPausePoint() {
        steps.value = steps.value + PauseStep()
        markDirty()
    }

    /** Drops the last step. Returns false when there was nothing to drop. */
    fun undo(): Boolean {
        val current = steps.value
        if (current.isEmpty()) return false
        steps.value = current.dropLast(1)
        markDirty()
        return true
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
        steps.value = current.map { if (it.id == step.id) step else it }
        dirty.value = true
        if (persist) persist()
    }

    fun removeStep(id: String) {
        val current = steps.value
        if (current.none { it.id == id }) return
        steps.value = current.filterNot { it.id == id }
        markDirty()
    }

    /** Inserts after [afterId], or at the end when it is null or unknown. */
    fun insertAfter(afterId: String?, step: Step, capturedOn: ScreenSpec) {
        if (screen == null) screen = capturedOn
        val current = steps.value
        val index = current.indexOfFirst { it.id == afterId }
        steps.value = if (index < 0) current + step else current.toMutableList().apply { add(index + 1, step) }
        markDirty()
    }

    /** Writes the draft after a run of non-persisting edits. */
    fun flush() = persist()

    fun clear() {
        steps.value = emptyList()
        sourceClipId = null
        screen = null
        dirty.value = false
        persist()
    }

    fun load(clip: Clip) {
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

    /** Name to offer when saving: the source clip's, or a generated one. */
    fun suggestedName(fallback: String): String =
        Repo.clipById(sourceClipId)?.name ?: fallback

    private fun markDirty() {
        dirty.value = true
        persist()
    }

    private fun persist() =
        Repo.writeWorkspace(WorkspaceSnapshot(steps.value, sourceClipId, screen, dirty.value))
}
