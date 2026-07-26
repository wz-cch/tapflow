package com.tapflow.android.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tapflow.android.R
import com.tapflow.android.data.Repo
import com.tapflow.android.data.Settings
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

            item { Section(R.string.settings_section_randomise) }
            item {
                SliderRow(
                    label = stringResource(R.string.settings_jitter_radius),
                    body = stringResource(R.string.settings_jitter_radius_body),
                    value = stringResource(R.string.value_px, settings.jitterRadiusPx),
                    position = settings.jitterRadiusPx.toFloat(),
                    range = 0f..Settings.JITTER_RADIUS_MAX.toFloat(),
                ) { Repo.updateSettings { s -> s.copy(jitterRadiusPx = it.roundToInt()) } }
            }
            item {
                SliderRow(
                    label = stringResource(R.string.settings_jitter_time),
                    value = stringResource(R.string.value_percent, settings.jitterTimePercent),
                    position = settings.jitterTimePercent.toFloat(),
                    range = 0f..Settings.JITTER_TIME_MAX.toFloat(),
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
    ) { onChange((it / granularityMs).roundToInt() * granularityMs.toLong()) }
}

@Composable
private fun SliderRow(
    label: String,
    value: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    body: String? = null,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium)
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
