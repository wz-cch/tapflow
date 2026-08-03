package com.tapflow.android.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.tapflow.android.R

/**
 * A number the user types instead of dragging a slider to.
 *
 * Sliders are fine for a range you can see the whole of, and hopeless for one you cannot: 0–30000 ms in a
 * finger's width, or a loop count where the "forever" end is a single pixel at the far left and the label
 * only reveals what it means once you have already landed on it.
 *
 * [current] and [range] are in the units the caller **displays**, and [onEntered] converts back. That
 * split is the whole point of passing this rather than reusing a slider's own range: several settings show
 * a percentage or a multiplier over a fraction they store, so a field wired to the stored units would take
 * "85" and write 85 where 0.85 was meant. Rows whose display and storage differ simply do not offer this —
 * a narrow range is what the slider is good at anyway.
 */
class TypedNumber(
    val current: Int,
    val range: IntRange,
    val onEntered: (Int) -> Unit,
)

/**
 * Asks for one number.
 *
 * Out of range refuses rather than clamping. Clamping turns a mistyped 5000 into a silent 500, which looks
 * like the entry was accepted; showing the bounds and keeping OK disabled says what is wrong and, on the
 * way, what the limits are — which nothing else on the row states.
 */
@Composable
fun NumberEntryDialog(title: String, entry: TypedNumber, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(entry.current.toString()) }
    val parsed = text.trim().toIntOrNull()
    val valid = parsed != null && parsed in entry.range

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = text.isNotBlank() && !valid,
                supportingText = {
                    Text(stringResource(R.string.number_entry_range, entry.range.first, entry.range.last))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    parsed?.let(entry.onEntered)
                    onDismiss()
                },
            ) { Text(stringResource(R.string.dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}
