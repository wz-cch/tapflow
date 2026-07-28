package com.tapflow.android.ui

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.tapflow.android.engine.EngineState
import com.tapflow.android.engine.TapFlowService
import kotlinx.coroutines.delay

/**
 * The three states the accessibility service can be in from the app's point of view.
 *
 * The middle one is the reason this exists. Checking only whether the service is bound cannot tell
 * "you never turned it on" apart from "you did, but it is not running right now" — and the second
 * happens routinely, because swiping the app off the recents list kills the process and the service
 * with it. Showing the same "please enable the accessibility service" card for both, with a greyed
 * out switch, is a dead end for anyone who already enabled it.
 */
enum class ServiceStatus { DISABLED, ENABLED_NOT_RUNNING, RUNNING }

@Composable
fun rememberServiceStatus(): ServiceStatus {
    val context = LocalContext.current
    val running by EngineState.serviceRunning.collectAsStateWithLifecycle()

    // Whether the switch is on in system settings has no broadcast to listen to, so it is polled.
    // Only while resumed, so a backgrounded app is not waking up once a second for nothing.
    var enabledInSettings by remember { mutableStateOf(isEnabledInSettings(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                enabledInSettings = isEnabledInSettings(context)
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    return when {
        running -> ServiceStatus.RUNNING
        enabledInSettings -> ServiceStatus.ENABLED_NOT_RUNNING
        else -> ServiceStatus.DISABLED
    }
}

/**
 * Reads the system list of enabled accessibility services.
 *
 * The stored entries can be either the full or the short component form depending on Android
 * version, so both are compared.
 */
private fun isEnabledInSettings(context: Context): Boolean {
    val enabled = runCatching {
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
    }.getOrNull()
    if (TextUtils.isEmpty(enabled)) return false

    val component = ComponentName(context.packageName, TapFlowService::class.java.name)
    val full = component.flattenToString()
    val short = component.flattenToShortString()

    return enabled!!.split(':').any { it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true) }
}

private const val POLL_INTERVAL_MS = 1000L
