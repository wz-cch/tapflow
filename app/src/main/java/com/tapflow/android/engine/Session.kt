package com.tapflow.android.engine

import com.tapflow.android.data.AppMode
import com.tapflow.android.data.LoadedClip
import com.tapflow.android.data.OpenFlow
import com.tapflow.android.data.Repo

/**
 * What is open, and which mode it is open in.
 *
 * One object owns every transition, because the rule it keeps is a rule *between* two pieces of state:
 * **at most one side ever holds anything.** Spread across call sites, that rule is a set of edges to
 * remember — "opening a clip must also close the flow" — and a forgotten edge is a bug. That is exactly
 * how opening a clip from the app once left the toolbar still playing a flow: four call sites did the
 * pairing by hand and one of them did not exist yet.
 *
 * None of these ask anything. Whether discarding needs a question is [needsConfirm]; asking is the
 * caller's job, because the two callers have nothing in common — the app puts up a dialog, the toolbar
 * puts up an overlay pad.
 *
 * None of them do IO either. Reading a file happens in [Repo] before anything here is called, which is what
 * keeps every one of these callable from the service's main thread — and is why the two "open" functions take
 * something already read rather than a file reference.
 */
object Session {

    /**
     * Whether discarding the workspace would lose something.
     *
     * `dirty`, not "has steps". A clip that was opened and left alone is already saved, so dropping it
     * costs two taps to get back, not any work — and asking about it would train the answer out of you,
     * which is what makes a warning about the case that matters get dismissed unread.
     */
    val needsConfirm: Boolean get() = Workspace.dirty.value

    /**
     * The flow a clip was opened *from*, so finishing with the clip goes back to it. Null the rest of the
     * time, which is most of the time.
     *
     * **This is navigation history, not part of the invariant above**, and the distinction is load-bearing.
     * It is deliberately not [Repo.openFlow]: that one says which flow the play button runs, and setting it
     * here would put an open flow and a dirty workspace on the two sides at once — precisely what this object
     * exists to prevent. Nothing that decides a mode may read this.
     *
     * A file reference rather than an id, like everything else now. It is also why coming back has to re-read
     * the flow: nothing was held open, so the only way to see the clip's edit reflected is to read the file
     * again — which is what [returnToFlow] does.
     */
    var returnToFlowRef: String? = null
        private set

    /**
     * The one deliberate way between modes.
     *
     * Emptying both sides is the point rather than a side effect: `clip → flow → clip` arrives back at an
     * empty clip mode. That keeps "what does play do" answerable from the mode alone, with no second
     * question about which of two open things wins.
     */
    fun switchMode(next: AppMode) {
        if (Repo.mode.value == next) return
        empty()
        Repo.setMode(next)
    }

    fun openClip(loaded: LoadedClip) {
        returnToFlowRef = null
        Repo.openFlow.value = null
        Workspace.load(loaded)
        Repo.setMode(AppMode.CLIP)
    }

    fun openFlow(flow: OpenFlow) {
        returnToFlowRef = null
        Workspace.clear()
        Repo.openFlow.value = flow
        Repo.setMode(AppMode.FLOW)
    }

    /**
     * Opens one of a flow's clips *above* the flow, so finishing with it returns there.
     *
     * The clip is opened exactly as it would be from anywhere else — same working copy, same detachment
     * from the file — so nothing about editing it is special. What the flow contributes is only where to
     * come back to.
     *
     * Set after [openClip], not instead of it: routing through the normal path is what guarantees the flow
     * side really is empty while the excursion runs, and the breadcrumb is then the *only* thing that makes
     * this different from having opened the clip from the recent list.
     *
     * Nothing about the flow is held open. It is already on disk — the flow editor writes on every change —
     * so there is nothing here that could be lost, and coming back re-reads it.
     */
    fun editClipFromFlow(flowRef: String, loaded: LoadedClip) {
        openClip(loaded)
        returnToFlowRef = flowRef
    }

    /**
     * Takes the breadcrumb, so the caller can go back to that flow. Null when there is nothing to go back to.
     *
     * Deliberately does not open the flow. Opening one means reading its file and every clip it references,
     * which is IO, and the only caller is a toolbar button on the service's main thread — so it hands the ref
     * to the flow editor, which reads it in the background as it does for every other way in.
     *
     * Consumed whatever the caller then makes of it, including a flow that has since been deleted: a bookmark
     * that survives failing to be followed is one that fires at the wrong moment later.
     */
    fun consumeReturnRef(): String? {
        val ref = returnToFlowRef
        returnToFlowRef = null
        return ref
    }

    /** Clip mode with nothing in it: the state a recording starts from. */
    fun startFresh() {
        empty()
        Repo.setMode(AppMode.CLIP)
    }

    /**
     * Deliberate exit — the toolbar being turned off.
     *
     * Emptying here is what gives the recovery question its meaning. Every intentional way out passes
     * through this, and [Workspace.clear] writes the draft back undirtied, so a dirty draft on disk can
     * only have been left by a process that died. No flag, no bookkeeping: the file is the evidence.
     *
     * The mode is not touched. It is the one thing worth coming back to.
     */
    fun close() = empty()

    private fun empty() {
        Workspace.clear()
        Repo.openFlow.value = null
        returnToFlowRef = null
    }
}
