package com.tapflow.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tapflow.android.BuildConfig
import com.tapflow.android.R
import com.tapflow.android.engine.CrashLog
import com.tapflow.android.engine.Diag

/**
 * Shows the engine's recent activity so it can be copied out of the device.
 *
 * logcat needs a computer, and a toast holds one sentence. Without this, working out why a gesture
 * was being cancelled meant guessing and shipping a build per guess.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // The revision counter is the recompose trigger; the text itself is pulled on read.
    val revision by Diag.revision.collectAsStateWithLifecycle()
    val body = remember(revision) { Diag.dump() }

    val crashRevision by CrashLog.revision.collectAsStateWithLifecycle()
    val crash = remember(crashRevision) { CrashLog.read() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diag_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        }
    ) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 16.dp)
        ) {
            // Above the timeline and in the error colour, because it is the one thing here that survives a
            // process death — so if it is present, it is almost certainly why you opened this screen.
            if (crash != null) {
                Text(
                    stringResource(R.string.diag_crash_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
                // The first few lines only. The whole thing goes on the clipboard; what is on screen is
                // just enough to see that something is there and to say what it was out loud.
                Text(
                    crash.lineSequence().take(6).joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    OutlinedButton(onClick = { context.copyToClipboard(withHeader(crash)) }) {
                        Text(stringResource(R.string.diag_crash_copy))
                    }
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    OutlinedButton(onClick = { CrashLog.clear() }) {
                        Text(stringResource(R.string.diag_crash_clear))
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Text(
                stringResource(R.string.diag_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(
                    enabled = body.isNotEmpty(),
                    onClick = { context.copyToClipboard(withHeader(body)) },
                ) { Text(stringResource(R.string.diag_copy)) }
                Spacer(Modifier.height(8.dp))
                Spacer(Modifier.padding(horizontal = 4.dp))
                OutlinedButton(onClick = { Diag.clear() }) {
                    Text(stringResource(R.string.diag_clear))
                }
            }
            Spacer(Modifier.height(12.dp))

            // Monospace and horizontally scrollable: the columns only line up if nothing wraps.
            Text(
                text = body.ifEmpty { stringResource(R.string.diag_empty) },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            )
        }
    }
}

/** The build is part of the report: which APK produced a timeline is half the answer. */
private fun withHeader(body: String) = "TapFlow ${BuildConfig.VERSION_NAME}\n$body"

private fun Context.copyToClipboard(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("TapFlow diagnostics", text))
    Toast.makeText(this, getString(R.string.diag_copied), Toast.LENGTH_SHORT).show()
}
