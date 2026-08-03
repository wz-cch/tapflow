package com.tapflow.android.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tapflow.android.R
import com.tapflow.android.engine.Session

/**
 * The app-side half of "this will throw away unsaved steps".
 *
 * Same wording and same two answers as the toolbar's version (TapFlowService.confirmDiscard). Two
 * implementations because the two sides share no UI toolkit — one is Compose, the other is a view on an
 * overlay that cannot take focus — but one string and one condition, so they cannot drift in meaning.
 *
 * The confirm button names the outcome instead of saying OK, and shares that string with the toolbar. An
 * AlertDialog supplies neither button, so this side never had the duplicated cancel the pad did; matching
 * the wording is what keeps the two from reading as different questions.
 *
 * Every caller wraps a *destructive* act, not the dialog that leads to one: opening a list is harmless
 * and you may well cancel out of it, so the question belongs at the pick, not at the open.
 */
@Composable
fun DiscardConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(R.string.discard_warning)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.discard_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

/**
 * Runs [action] now if nothing would be lost, otherwise hands back the pending action to be confirmed.
 *
 * Written as a helper rather than repeated at each call site because forgetting the check is silent —
 * the action simply happens and the steps are gone.
 */
inline fun guardDiscard(action: () -> Unit, defer: () -> Unit) {
    if (Session.needsConfirm) defer() else action()
}
