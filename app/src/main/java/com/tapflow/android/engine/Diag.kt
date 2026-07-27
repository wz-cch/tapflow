package com.tapflow.android.engine

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A short, in-app record of what the engine just did.
 *
 * This exists because logcat is not reachable from a phone in the field, and a toast can only carry
 * one sentence. Several rounds of this project were spent guessing at why gestures were being
 * cancelled, with each guess costing a build; a timeline the user can copy and paste ends that.
 *
 * Deliberately plain text and bounded. It is a diagnostic aid, not a feature, and nothing depends on
 * its contents.
 */
object Diag {

    private const val CAPACITY = 250
    private const val TAG = "TapFlowDiag"

    private val entries = ArrayDeque<String>(CAPACITY)
    private var origin = SystemClock.uptimeMillis()

    /** Bumped on every write so the UI can recompose. */
    val revision = MutableStateFlow(0)

    @Synchronized
    fun log(message: String) {
        val at = SystemClock.uptimeMillis() - origin
        val line = "%7d  %s".format(at, message)
        if (entries.size >= CAPACITY) entries.removeFirst()
        entries.addLast(line)
        Log.d(TAG, line)
        revision.value = revision.value + 1
    }

    /** Restarts the clock so a fresh attempt is easy to read. */
    @Synchronized
    fun clear() {
        entries.clear()
        origin = SystemClock.uptimeMillis()
        revision.value = revision.value + 1
    }

    @Synchronized
    fun dump(): String =
        if (entries.isEmpty()) "" else entries.joinToString("\n")

    @Synchronized
    fun isEmpty(): Boolean = entries.isEmpty()
}
