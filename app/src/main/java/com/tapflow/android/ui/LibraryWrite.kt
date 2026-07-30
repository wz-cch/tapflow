package com.tapflow.android.ui

import android.content.Context
import android.widget.Toast
import com.tapflow.android.R

/**
 * Runs a library write and says so when the folder refused it.
 *
 * Saving a clip or a flow can now fail — the folder lives outside the app, so it can be moved, deleted,
 * or sit on a card that has been ejected. `Repo.upsertClip` and `upsertFlow` report that as `false`, and
 * Kotlin will not make anyone read it.
 *
 * A dropped failure is the worst shape this can take. The write does not land *and* the in-memory list is
 * left alone, so the screen simply does not change: the user's tap appears to have done nothing at all,
 * with no message and nothing in the UI to suggest storage was involved. One wrapper at each call site
 * makes that impossible, and gives every one of them the same sentence.
 *
 * Returns what the write returned, so a caller that should not navigate on failure can check.
 */
fun Context.wroteToLibrary(write: () -> Boolean): Boolean {
    val ok = write()
    if (!ok) Toast.makeText(this, getString(R.string.toast_save_failed), Toast.LENGTH_LONG).show()
    return ok
}
