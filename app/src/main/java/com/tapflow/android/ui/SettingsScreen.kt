package com.tapflow.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tapflow.android.R
import com.tapflow.android.data.FileLibrary
import com.tapflow.android.data.FolderStore
import com.tapflow.android.data.Repo
import com.tapflow.android.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settings by Repo.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        }
    ) { insets ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 16.dp),
        ) {
            item { Section(R.string.settings_section_storage) }
            item { FolderRow() }

            item { Section(R.string.settings_section_defaults) }
            item {
                MsSlider(R.string.settings_default_gap, settings.defaultGapMs, 0f, 2000f, 50f) { ms ->
                    Repo.updateSettings { it.copy(defaultGapMs = ms) }
                }
            }
            item {
                MsSlider(R.string.settings_default_tap, settings.defaultTapMs, 20f, 1000f, 5f) { ms ->
                    Repo.updateSettings { it.copy(defaultTapMs = ms) }
                }
            }
            item {
                MsSlider(R.string.settings_default_swipe, settings.defaultSwipeMs, 50f, 3000f, 50f) { ms ->
                    Repo.updateSettings { it.copy(defaultSwipeMs = ms) }
                }
            }

            item { Section(R.string.settings_section_playback) }
            item {
                // 0 means "until stopped", so the label has to say that rather than show a zero.
                val loops = settings.defaultLoopCount
                SliderRow(
                    label = stringResource(R.string.settings_loop_count),
                    value = if (loops == 0) stringResource(R.string.settings_loop_forever)
                    else stringResource(R.string.value_times, loops),
                    position = loops.toFloat(),
                    range = 0f..500f,
                    steps = 0,
                    // The reason this row needed typing at all. "Forever" is 0, 0 is one pixel at the far
                    // left of a 500-wide slider, and the label only says "forever" once you have landed
                    // on it — so the setting existed and could not be found.
                    typed = TypedNumber(loops, 0..500) { entered ->
                        Repo.updateSettings { s -> s.copy(defaultLoopCount = entered) }
                    },
                ) { Repo.updateSettings { s -> s.copy(defaultLoopCount = it.roundToInt()) } }
            }
            item {
                SliderRow(
                    label = stringResource(R.string.settings_speed),
                    value = stringResource(R.string.value_multiplier, "%.1f".format(settings.speed)),
                    position = settings.speed,
                    range = 0.25f..4f,
                ) { Repo.updateSettings { s -> s.copy(speed = (it * 20).roundToInt() / 20f) } }
            }
            item {
                MsSlider(R.string.settings_start_delay, settings.startDelayMs, 0f, 10_000f, 500f) { ms ->
                    Repo.updateSettings { it.copy(startDelayMs = ms) }
                }
            }
            item {
                MsSlider(R.string.settings_loop_interval, settings.loopIntervalMs, 0f, 30_000f, 250f) { ms ->
                    Repo.updateSettings { it.copy(loopIntervalMs = ms) }
                }
            }

            item { Section(R.string.settings_section_randomise) }
            item {
                SliderRow(
                    label = stringResource(R.string.settings_jitter_radius),
                    body = stringResource(R.string.settings_jitter_radius_body),
                    value = stringResource(R.string.value_px, settings.jitterRadiusPx),
                    position = settings.jitterRadiusPx.toFloat(),
                    range = 0f..Settings.JITTER_RADIUS_MAX.toFloat(),
                    typed = TypedNumber(settings.jitterRadiusPx, 0..Settings.JITTER_RADIUS_MAX) { px ->
                        Repo.updateSettings { s -> s.copy(jitterRadiusPx = px) }
                    },
                ) { Repo.updateSettings { s -> s.copy(jitterRadiusPx = it.roundToInt()) } }
            }
            item {
                SliderRow(
                    label = stringResource(R.string.settings_jitter_time),
                    value = stringResource(R.string.value_percent, settings.jitterTimePercent),
                    position = settings.jitterTimePercent.toFloat(),
                    range = 0f..Settings.JITTER_TIME_MAX.toFloat(),
                    // Shown as a percent and stored as a percent, so the field can offer it directly —
                    // unlike opacity and the dim layer, which display a percent over a 0–1 fraction.
                    typed = TypedNumber(settings.jitterTimePercent, 0..Settings.JITTER_TIME_MAX) { pct ->
                        Repo.updateSettings { s -> s.copy(jitterTimePercent = pct) }
                    },
                ) { Repo.updateSettings { s -> s.copy(jitterTimePercent = it.roundToInt()) } }
            }

            item { Section(R.string.settings_section_recording) }
            item {
                SwitchRow(
                    label = stringResource(R.string.settings_replay_each),
                    body = stringResource(R.string.settings_replay_each_body),
                    checked = settings.replayEachGesture,
                ) { Repo.updateSettings { s -> s.copy(replayEachGesture = it) } }
            }
            item {
                MsSlider(R.string.settings_replay_delay, settings.replayDelayMs, 0f, 500f, 10f) { ms ->
                    Repo.updateSettings { it.copy(replayDelayMs = ms) }
                }
            }

            item { Section(R.string.settings_section_editing) }
            item {
                SliderRow(
                    label = stringResource(R.string.settings_edit_handle),
                    body = stringResource(R.string.settings_edit_handle_body),
                    value = stringResource(R.string.value_dp, settings.editHandleDp),
                    position = settings.editHandleDp.toFloat(),
                    range = Settings.EDIT_HANDLE_RANGE,
                    typed = TypedNumber(
                        settings.editHandleDp,
                        Settings.EDIT_HANDLE_RANGE.start.toInt()..Settings.EDIT_HANDLE_RANGE.endInclusive.toInt(),
                    ) { dp -> Repo.updateSettings { s -> s.copy(editHandleDp = dp) } },
                ) { dp ->
                    Repo.updateSettings { s -> s.copy(editHandleDp = (dp / 4f).roundToInt() * 4) }
                }
            }

            item { Section(R.string.settings_section_appearance) }
            item {
                SliderRow(
                    label = stringResource(R.string.settings_ui_scale),
                    value = stringResource(R.string.value_multiplier, "%.1f".format(settings.uiScale)),
                    position = settings.uiScale,
                    range = Settings.UI_SCALE_RANGE,
                ) { Repo.updateSettings { s -> s.copy(uiScale = (it * 20).roundToInt() / 20f) } }
            }
            item {
                SliderRow(
                    label = stringResource(R.string.settings_ui_opacity),
                    value = stringResource(R.string.value_percent, (settings.uiOpacity * 100).roundToInt()),
                    position = settings.uiOpacity,
                    range = Settings.UI_OPACITY_RANGE,
                ) { Repo.updateSettings { s -> s.copy(uiOpacity = (it * 20).roundToInt() / 20f) } }
            }
            item {
                SwitchRow(
                    label = stringResource(R.string.settings_show_timer),
                    checked = settings.showTimer,
                ) { Repo.updateSettings { s -> s.copy(showTimer = it) } }
            }
            item {
                SwitchRow(
                    label = stringResource(R.string.settings_show_markers_idle),
                    body = stringResource(R.string.settings_show_markers_idle_body),
                    checked = settings.showMarkersWhenIdle,
                ) { Repo.updateSettings { s -> s.copy(showMarkersWhenIdle = it) } }
            }

            item { Section(R.string.settings_section_screen) }
            item {
                SwitchRow(
                    label = stringResource(R.string.settings_keep_screen_on),
                    body = stringResource(R.string.settings_keep_screen_on_body),
                    checked = settings.keepScreenOn,
                ) { Repo.updateSettings { s -> s.copy(keepScreenOn = it) } }
            }
            item {
                SwitchRow(
                    label = stringResource(R.string.settings_dim),
                    body = stringResource(R.string.settings_dim_body),
                    checked = settings.dimOverlay,
                ) { Repo.updateSettings { s -> s.copy(dimOverlay = it) } }
            }
            if (settings.dimOverlay) {
                item {
                    SliderRow(
                        label = stringResource(R.string.settings_dim_alpha),
                        value = stringResource(R.string.value_percent, (settings.dimAlpha * 100).roundToInt()),
                        position = settings.dimAlpha,
                        range = 0.3f..1f,
                    ) { Repo.updateSettings { s -> s.copy(dimAlpha = (it * 20).roundToInt() / 20f) } }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { Repo.resetSettings() }) {
                    Text(stringResource(R.string.settings_reset))
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/**
 * Which folder holds the saved clips, and the way to change it.
 *
 * Shows a folder *name* rather than a path because SAF does not give one — the tree Uri's document id
 * decodes to something like `Documents/TapFlow` for internal storage and cards, and providers are free
 * to return something opaque instead. That is the limit of what the platform exposes.
 *
 * Clearing does not delete anything. It releases the permission and forgets where the library was, so
 * the next save asks again; the files stay exactly where they are.
 */
@Composable
private fun FolderRow() {
    val usable by FolderStore.usable.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { Repo.useFolder(uri) }
            busy = false
        }
    }

    // API 28 and below: the path is fixed, so what can be missing is the permission that lets us create it.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { Repo.useDefaultFolder() }
            busy = false
        }
    }

    // Re-probed on arrival: the folder may have gone away since it was last written to, and this screen
    // is one of the few places that can say so before a save fails.
    LaunchedEffect(Unit) {
        if (FolderStore.isConfigured) withContext(Dispatchers.IO) { FolderStore.refreshUsable() }
    }

    val needsPermission = FolderStore.needsPermission

    Column(Modifier.padding(top = 12.dp)) {
        Text(stringResource(R.string.folder_setting), style = MaterialTheme.typography.bodyLarge)
        // The path is always shown where there is a real one, even before permission is granted — knowing
        // where the clips will go is useful in itself, and it is what you back up.
        Text(
            when {
                busy -> stringResource(R.string.folder_reading)
                needsPermission -> stringResource(R.string.folder_permission_title)
                !FolderStore.isConfigured -> stringResource(R.string.folder_none)
                !usable -> stringResource(R.string.folder_lost_title)
                else -> FolderStore.displayName().orEmpty()
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (!busy && (needsPermission || (FolderStore.isConfigured && !usable))) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    if (FolderStore.picksFolder) picker.launch(null)
                    else permission.launch(FileLibrary.PERMISSION)
                },
                enabled = !busy,
            ) {
                Text(
                    stringResource(
                        when {
                            needsPermission -> R.string.folder_permission_action
                            FolderStore.isConfigured -> R.string.folder_change
                            else -> R.string.folder_pick_action
                        }
                    )
                )
            }
            // Only where forgetting means something. On the versions with a fixed path there is no choice
            // to undo, and a button that clears nothing is worse than no button.
            if (FolderStore.canForget && FolderStore.isConfigured) {
                OutlinedButton(onClick = { Repo.forgetFolder() }, enabled = !busy) {
                    Text(stringResource(R.string.folder_clear))
                }
            }
        }
    }
}

@Composable
private fun Section(titleRes: Int) {
    Spacer(Modifier.height(20.dp))
    Text(
        stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    HorizontalDivider(Modifier.padding(top = 4.dp))
}

/** Convenience wrapper for the many millisecond settings, which all share the same shape. */
@Composable
private fun MsSlider(
    labelRes: Int,
    valueMs: Long,
    min: Float,
    max: Float,
    granularityMs: Float,
    onChange: (Long) -> Unit,
) {
    SliderRow(
        label = stringResource(labelRes),
        value = stringResource(R.string.value_ms, valueMs),
        position = valueMs.toFloat().coerceIn(min, max),
        range = min..max,
        // Typed values are taken exactly, not snapped to the granularity. The granularity is there so
        // that *dragging* lands on round numbers; typing is the way round it, and rounding 137 to 150
        // would ignore the one input that was precise on purpose.
        typed = TypedNumber(valueMs.toInt(), min.toInt()..max.toInt()) { onChange(it.toLong()) },
    ) { onChange((it / granularityMs).roundToInt() * granularityMs.toLong()) }
}

/**
 * @param typed offers the value as something to type as well as drag. Opt-in per row, because it is only
 *   correct where the number shown *is* the number stored — see [TypedNumber]. The value reads as tappable
 *   by being in the accent colour, which is the only affordance a one-line row has room for.
 */
@Composable
private fun SliderRow(
    label: String,
    value: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    body: String? = null,
    steps: Int = 0,
    typed: TypedNumber? = null,
    onChange: (Float) -> Unit,
) {
    var entering by remember { mutableStateOf(false) }

    Column(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            if (typed == null) {
                Text(value, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { entering = true }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        if (entering && typed != null) {
            NumberEntryDialog(title = label, entry = typed) { entering = false }
        }
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = position.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    body: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (body != null) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
