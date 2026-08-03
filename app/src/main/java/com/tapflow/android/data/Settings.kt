package com.tapflow.android.data

import com.tapflow.android.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How densely on-screen sequence markers and the step list are shown.
 * Cycled by the eye button on the toolbar.
 */
@Serializable
enum class MarkerDensity {
    /** Keep every marker, step list visible. */
    @SerialName("all") ALL,

    /** Keep the last 10 markers, step list visible. */
    @SerialName("recent") RECENT,

    /** Keep only the last marker and fade it out, step list collapsed. For seeing the app below. */
    @SerialName("hidden") HIDDEN,
    ;

    fun next(): MarkerDensity = entries[(ordinal + 1) % entries.size]

    /** How many markers to keep on screen. */
    val keepCount: Int
        get() = when (this) {
            ALL -> Int.MAX_VALUE
            RECENT -> 10
            HIDDEN -> 1
        }
}

/**
 * What a real finger landing part way through an injected gesture does to the run.
 *
 * The framework cancels every in-flight injected gesture the moment real input arrives — the two streams
 * are not merged, real input simply wins — and it reports the cancellation without a reason. So this is not
 * "a gesture failed": it is the user taking over, and **both** touches reached the app below, ours as far as
 * it had got plus theirs. That is why none of these three is a retry.
 *
 * Other auto-clickers mostly [IGNORE] this, largely because they never detect it. We do, so the choice is
 * real, and pausing is the default: it is the one that assumes reaching for the screen was deliberate.
 */
@Serializable
enum class TouchPolicy {
    /** End the run. Continuing means starting over, which is what stopping has always meant here. */
    @SerialName("stop") STOP,

    /** Hold at this step so the missed one can be done by hand, then carry on. */
    @SerialName("pause") PAUSE,

    /** Carry straight on, accepting that this step landed partly or not at all. */
    @SerialName("ignore") IGNORE,
    ;

    val labelRes: Int
        get() = when (this) {
            STOP -> R.string.touch_stop
            PAUSE -> R.string.touch_pause
            IGNORE -> R.string.touch_ignore
        }
}

/**
 * What a gesture that never reached the app does, once the retries are used up.
 *
 * A different situation from [TouchPolicy] despite looking alike from the outside. Here nothing was
 * delivered — the description was rejected, or the framework had no injector to hand it to — so there is no
 * half-applied touch to reason about, and retrying is safe.
 */
@Serializable
enum class FailurePolicy {
    /** Move on to the next step. The count is reported when the run ends, so it is never silent. */
    @SerialName("skip") SKIP,

    /** Hold at this step. Right when the steps depend on each other. */
    @SerialName("pause") PAUSE,
    ;

    val labelRes: Int
        get() = when (this) {
            SKIP -> R.string.failure_skip
            PAUSE -> R.string.failure_pause
        }
}

@Serializable
data class Settings(
    // --- Defaults applied to newly added actions ---
    /** Default gap between actions. */
    val defaultGapMs: Long = 50,
    /** How long a manually added tap is held. */
    val defaultTapMs: Long = 75,
    /** Default swipe duration. */
    val defaultSwipeMs: Long = 300,
    /** Default two-finger pinch duration. */
    val defaultPinchMs: Long = 3000,

    // --- Execution ---
    /** 0 means run until stopped. */
    val defaultLoopCount: Int = 1,
    val speed: Float = 1f,
    /** Countdown after pressing play. */
    val startDelayMs: Long = 3000,
    /**
     * Gap between one pass over the script and the next, when it loops.
     *
     * Its own quantity, and not the first step's [Step.delayBefore] — that one is the lead-in *before step
     * one*, recorded as a default rather than measured against anything, so leaving it to separate the
     * passes made them run all but back to back. The same distinction [RepeatableStep.repeatIntervalMs]
     * draws for a single step, one level up.
     *
     * Also not [startDelayMs]: that countdown exists so you can switch to the target app, which happens
     * once. Reusing it here would put a three-second wait between every pass of a hundred-loop run.
     */
    val loopIntervalMs: Long = 500,

    // --- When a step does not land ---
    //
    // Two settings and not one, because the two triggers are different events that happen to arrive
    // through the same callback. See TouchPolicy and FailurePolicy. Both default to pausing: a script
    // whose step 3 never landed and which then ran steps 4 to 40 anyway is the failure that looks like
    // success, and that is the one worth interrupting for.

    val onRealTouch: TouchPolicy = TouchPolicy.PAUSE,

    /** Extra attempts at a gesture that never landed. 0 gives up at once. */
    val failureRetries: Int = 1,

    val onGestureFailure: FailurePolicy = FailurePolicy.PAUSE,

    // --- Randomisation ---
    /**
     * Each replay offsets the whole stroke by a random vector within this radius. 0 disables it.
     * The offset is applied per stroke, not per sample, otherwise swipe paths turn into zigzags.
     */
    val jitterRadiusPx: Int = 0,
    /** Randomise delays and durations by plus or minus this percentage. 0 disables it. */
    val jitterTimePercent: Int = 0,

    // --- Recording ---
    /**
     * Replay each gesture to the app below right after recording it, so the screen actually
     * advances. Turning this off gives pure blind recording, where the screen never moves.
     */
    val replayEachGesture: Boolean = true,
    /** How long to wait after a replayed gesture for the target app to finish animating. */
    val replayDelayMs: Long = 80,

    // --- Editing ---
    /**
     * Diameter, in dp, of a grab handle while editing.
     *
     * One number decides three things, which is why it is a setting and not three constants. It is the
     * ring drawn on the selected marker; it is the radius that ring makes visible, so grabbing and
     * selecting use the same figure by construction; and it is the separation two endpoints need before
     * both get their own ring, since below one diameter apart they would have no territory of their own.
     *
     * A setting rather than a tuned constant because fingers differ and the right value is not findable
     * on paper. The default is the radius the code used before it was adjustable, so nothing changes for
     * anyone who leaves it alone.
     */
    val editHandleDp: Int = 52,

    // --- Appearance ---
    val uiScale: Float = 1f,
    val uiOpacity: Float = 1f,
    val showTimer: Boolean = true,
    val markerDensity: MarkerDensity = MarkerDensity.RECENT,

    /**
     * Keep the markers painted while idle and during playback, not only while recording or editing.
     *
     * Off by default, and that default matters. Painting them needs a full-screen window, and such a
     * window makes every touch every app receives carry FLAG_WINDOW_IS_OBSCURED. Any view with
     * filterTouchesWhenObscured set then discards the touch, so those apps stop responding entirely —
     * to a real finger as much as to an injected gesture.
     */
    val showMarkersWhenIdle: Boolean = false,

    // --- Screen ---
    /** Keep the screen awake while recording or replaying; injected gestures do nothing once off. */
    val keepScreenOn: Boolean = true,
    /** Cover the screen with a black layer during replay. The screen stays on; only useful idling. */
    val dimOverlay: Boolean = false,
    val dimAlpha: Float = 0.85f,
) {
    companion object {
        val DEFAULT = Settings()

        const val JITTER_RADIUS_MAX = 150
        const val JITTER_TIME_MAX = 50
        const val MAX_LOOP_COUNT = 9999

        /**
         * Ceiling on [failureRetries].
         *
         * A missing gesture injector is not a passing condition — it stays broken until the service is
         * toggled or the phone restarted — so retries against it all fail, each costing the framework's
         * one-second timeout. Ten is already fifteen seconds on one step.
         */
        const val MAX_FAILURE_RETRIES = 10
        val SPEED_RANGE = 0.25f..4f
        /** Small enough to still point at a coordinate, large enough for a thumb. */
        val EDIT_HANDLE_RANGE = 36f..96f
        val UI_SCALE_RANGE = 0.7f..1.5f
        val UI_OPACITY_RANGE = 0.3f..1.0f
    }
}
