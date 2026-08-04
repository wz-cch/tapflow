package com.tapflow.android.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tapflow.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs a file write off the main thread, and says so when the file refused it.
 *
 * **Both halves earn their place.** Off the main thread, because writing goes through a ContentProvider on
 * API 29+ and a slow provider showed up on a device as the UI locking up rather than as a save taking a
 * moment. And reported, because `Repo.saveClip` and `saveFlow` return `false` and Kotlin will not make
 * anyone read it — a dropped failure is the worst shape this can take: the write does not land, the screen
 * does not change, and the tap appears to have done nothing at all with no mention of storage.
 */
fun CoroutineScope.writeFile(context: Context, write: () -> Boolean): Job = launch {
    val ok = withContext(Dispatchers.IO) { write() }
    if (!ok) Toast.makeText(context, context.getString(R.string.toast_save_failed), Toast.LENGTH_LONG).show()
}

/**
 * Shown while a file is being read or written.
 *
 * Opening a flow reads the flow *and* every clip in it, which on a document provider is one round trip each.
 * That is quick and it is not free, and a screen that does nothing for half a second after a tap reads as a
 * tap that missed.
 */
@Composable
fun BusyDialog() {
    AlertDialog(
        onDismissRequest = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.busy_reading))
            }
        },
        confirmButton = {},
    )
}
