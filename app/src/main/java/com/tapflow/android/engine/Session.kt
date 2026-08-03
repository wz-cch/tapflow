package com.tapflow.android.engine

import com.tapflow.android.data.AppMode
import com.tapflow.android.data.Clip
import com.tapflow.android.data.Flow
import com.tapflow.android.data.Repo

/**
 * What is loaded, and which mode it is loaded in.
 *
 * One object owns every transition, because the rule it keeps is a rule *between* two pieces of state:
 * **at most one side ever holds anything.** Spread across call sites, that rule is a set of edges to
 * remember — "loading a clip must also unload the flow" — and a forgotten edge is a bug. That is exactly
 * how loading a clip from the app once left the toolbar still playing a flow: four call sites did the
 * pairing by hand and one of them did not exist yet.
 *
 * None of these ask anything. Whether discarding needs a question is [needsConfirm]; asking is the
 * caller's job, because the two callers have nothing in common — the app puts up a dialog, the toolbar
 * puts up an overlay pad.
 */
object Session {

    /**
     * Whether discarding the workspace would lose something.
     *
     * `dirty`, not "has steps". A clip that was loaded and left alone is already saved, so dropping it
     * costs two taps to get back, not any work — and asking about it would train the answer out of you,
     * which is what makes a warning about the case that matters get dismissed unread.
     */
    val needsConfirm: Boolean get() = Workspace.dirty.value

    /**
     * The flow a clip was opened *from*, so finishing with the clip goes back to it. Null the rest of the
     * time, which is most of the time.
     *
     * **This is navigation history, not part of the invariant above**, and the distinction is load-bearing.
     * It is deliberately not `Repo.currentFlowId`: that one says which flow the play button runs, and
     * setting it here would put a loaded flow and a dirty workspace on the two sides at once — precisely
     * what this object exists to prevent. Nothing that decides a mode may read this.
     *
     * It lives here anyway because every deliberate transition already passes through this object, which
     * makes it the only place that can guarantee the breadcrumb is dropped rather than left pointing at a
     * flow nobody is inside any more. Every entry point below clears it; exactly one sets it.
     */
    var returnToFlowId: String? = null
        private set

    /**
     * The one deliberate way between modes.
     *
     * Emptying both sides is the point rather than a side effect: `clip → flow → clip` arrives back at an
     * empty clip mode. That keeps "what does play do" answerable from the mode alone, with no second
     * question about which of two loaded things wins.
     */
    fun switchMode(next: AppMode) {
        if (Repo.mode.value == next) return
        empty()
        Repo.setMode(next)
    }

    fun loadClip(clip: Clip) {
        returnToFlowId = null
        Repo.setCurrentFlow(null)
        Workspace.load(clip)
        Repo.setMode(AppMode.CLIP)
    }

    fun loadFlow(flow: Flow) {
        returnToFlowId = null
        Workspace.clear()
        Repo.setCurrentFlow(flow.id)
        Repo.setMode(AppMode.FLOW)
    }

    /**
     * Opens one of a flow's clips *above* the flow, so finishing with it returns there.
     *
     * The clip is loaded exactly as it would be from anywhere else — same working copy, same detachment
     * from the file — so nothing about editing it is special. What the flow contributes is only where to
     * come back to.
     *
     * Set after [loadClip], not instead of it: routing through the normal path is what guarantees the flow
     * side really is empty while the excursion runs, and the breadcrumb is then the *only* thing that makes
     * this different from having loaded the clip from the library.
     *
     * Nothing about the flow is held open. It is already on disk — the flow editor writes on every change —
     * so there is nothing here that could be lost, and coming back re-reads it.
     */
    fun editClipFromFlow(flowId: String, clip: Clip) {
        loadClip(clip)
        returnToFlowId = flowId
    }

    /**
     * Back to the flow the current clip came from. Returns it, or null if there is nothing to go back to.
     *
     * The breadcrumb is consumed whatever happens, including when the flow has since been deleted: a
     * bookmark that survives failing to be followed is one that fires at the wrong moment later.
     */
    fun returnToFlow(): Flow? {
        val flow = Repo.flowById(returnToFlowId)
        returnToFlowId = null
        flow?.let { loadFlow(it) }
        return flow
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
        Repo.setCurrentFlow(null)
        returnToFlowId = null
    }
}
