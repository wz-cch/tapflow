package com.tapflow.android.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last crash, kept on disk so it outlives the process that produced it.
 *
 * [Diag] is in memory, which makes it useless for exactly the failure worth reporting most: a crash takes
 * the process and the timeline with it. And the two devices this is tested on cannot reach logcat — that
 * needs a computer — so without this a crash report is "it closed", and every guess costs a build. That is
 * the same reasoning that produced the diagnostics screen; this is the piece it was missing.
 *
 * Deliberately writes the *previous* [Diag] timeline alongside the stack trace. Where it crashed is half
 * the answer; what it had just been doing is the other half.
 */
object CrashLog {

    private const val TAG = "TapFlowCrash"
    private const val FILE = "last-crash.txt"

    /** Bumped when a crash is stored or cleared, so the diagnostics screen recomposes. */
    val revision = MutableStateFlow(0)

    private var file: File? = null
    private var installed = false

    /**
     * Records uncaught exceptions, then hands on to whatever was there before.
     *
     * Chaining rather than replacing is not politeness: the default handler is what actually ends the
     * process and shows the system dialog. Swallowing it would leave a wedged app with no window.
     *
     * Idempotent, because every entry point calls this and any of them can be first.
     */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        file = File(context.filesDir, FILE)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Guarded: a handler that throws replaces a useful crash with a useless one.
            runCatching { store(thread, error) }
                .onFailure { Log.e(TAG, "Could not record the crash", it) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun store(thread: Thread, error: Throwable) {
        val target = file ?: return
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        target.writeText(
            buildString {
                appendLine("when:   $stamp")
                appendLine("thread: ${thread.name}")
                appendLine()
                appendLine(trace.trimEnd())
                // The timeline leading up to it. Often the part that actually identifies the cause.
                val timeline = Diag.dump()
                if (timeline.isNotEmpty()) {
                    appendLine()
                    appendLine("--- what the engine was doing ---")
                    appendLine(timeline)
                }
            }
        )
        revision.value = revision.value + 1
    }

    /** The stored crash, or null when there is none. */
    @Synchronized
    fun read(): String? = file
        ?.takeIf { it.isFile }
        ?.let { runCatching { it.readText() }.getOrNull() }
        ?.takeIf { it.isNotBlank() }

    @Synchronized
    fun clear() {
        runCatching { file?.delete() }
        revision.value = revision.value + 1
    }
}
