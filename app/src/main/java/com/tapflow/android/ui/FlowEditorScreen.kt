package com.tapflow.android.ui

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tapflow.android.R
import com.tapflow.android.data.Clip
import com.tapflow.android.data.ClipNode
import com.tapflow.android.data.Flow
import com.tapflow.android.data.Repo
import com.tapflow.android.data.Settings
import com.tapflow.android.engine.Session
import com.tapflow.android.text.clipSummary
import kotlin.math.roundToInt

/**
 * Arranges the clips in one flow.
 *
 * A plain list, and deliberately nothing more. This screen's object is the *clip*; it cannot edit what is
 * inside one, so there are no coordinates to drag, no durations to nudge, nothing that needs the overlay.
 * If a step inside a clip needs changing, that happens where clips are edited.
 *
 * Every change writes straight back through [Repo.upsertFlow]. A flow is a list of references with nothing
 * accumulating in it, so there is no unsaved state to protect and therefore no save button — the same
 * reason the toolbar has none in flow mode.
 *
 * "Straight back" now means a file in the user's folder, so every one of those writes can fail and every
 * one goes through [wroteToLibrary]. Without it the in-memory flow would be left untouched too, and the
 * screen would simply not change — a tap that silently did nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
/**
 * @param onEditClip one of this flow's clips is to be opened for editing. The screen has already loaded it
 *   and left the breadcrumb; what remains is handing over to the toolbar, which only the activity can do.
 */
@Composable
fun FlowEditorScreen(flowId: String, onBack: () -> Unit, onEditClip: () -> Unit) {
    val context = LocalContext.current
    val flows by Repo.flows.collectAsStateWithLifecycle()
    val clips by Repo.clips.collectAsStateWithLifecycle()
    val flow = flows.firstOrNull { it.id == flowId }

    // The flow can vanish under this screen — deleted from the toolbar, for instance — and there is
    // nothing to show then.
    if (flow == null) {
        onBack()
        return
    }

    var addingClip by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(flow.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                LoopRow(flow) { updated -> context.wroteToLibrary { Repo.upsertFlow(updated) } }
            }

            if (flow.clips.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.flow_editor_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            itemsIndexed(flow.clips) { index, node ->
                ClipNodeRow(
                    position = index + 1,
                    node = node,
                    clip = clips.firstOrNull { it.id == node.clipId },
                    canMoveUp = index > 0,
                    canMoveDown = index < flow.clips.lastIndex,
                    onMove = { delta -> context.wroteToLibrary { Repo.upsertFlow(flow.moved(index, delta)) } },
                    onRemove = {
                        context.wroteToLibrary { Repo.upsertFlow(flow.copy(clips = flow.clips.minusAt(index))) }
                    },
                    onOpenSettings = { editing = index },
                    // Nothing to confirm. The flow is written on every change here, so it is already on
                    // disk, and flow mode keeps the workspace empty — so leaving for the clip cannot lose
                    // anything on either side.
                    onEditClip = { clip ->
                        Session.editClipFromFlow(flow.id, clip)
                        onEditClip()
                    },
                )
            }

            item {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { addingClip = true }) {
                    Text(stringResource(R.string.flow_editor_add))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (addingClip) {
        // The same clip list the overlay's load dialog shows, for the same reason: one place that knows
        // how to present a clip means the two cannot drift.
        PickClipDialog(
            clips = clips,
            onDismiss = { addingClip = false },
        ) { clip ->
            context.wroteToLibrary { Repo.upsertFlow(flow.copy(clips = flow.clips + ClipNode(clipId = clip.id))) }
            addingClip = false
        }
    }

    editing?.let { index ->
        val node = flow.clips.getOrNull(index)
        if (node == null) {
            editing = null
        } else {
            ClipNodeSettingsDialog(
                position = index + 1,
                node = node,
                onDismiss = { editing = null },
            ) { updated ->
                context.wroteToLibrary { Repo.upsertFlow(flow.copy(clips = flow.clips.replacedAt(index, updated))) }
                editing = null
            }
        }
    }
}

/**
 * The flow's loop count.
 *
 * Writes on release, not on every value change. Saving a flow now means a file in a folder the user
 * chose, reached through a ContentProvider — so writing per tick would be dozens of round trips for one
 * drag, and the slider would stutter against its own saves. Dragging updates a local number; letting go
 * commits it. The clip-node dialog needs none of this: it already keeps its three sliders local and
 * writes once, on its confirm button.
 */
@Composable
private fun LoopRow(flow: Flow, onChange: (Flow) -> Unit) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    var entering by remember { mutableStateOf(false) }
    val loops = dragging ?: flow.loopCount

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.settings_loop_count),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                // Tappable for the same reason as the global loop count, and typing also reaches past the
                // slider's own ceiling: it stops at 100 where the setting allows more.
                Text(
                    if (loops == 0) stringResource(R.string.settings_loop_forever)
                    else stringResource(R.string.value_times, loops),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { entering = true }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            if (entering) {
                NumberEntryDialog(
                    title = stringResource(R.string.settings_loop_count),
                    entry = TypedNumber(flow.loopCount, 0..Settings.MAX_LOOP_COUNT) { entered ->
                        onChange(flow.copy(loopCount = entered))
                    },
                ) { entering = false }
            }
            Text(
                stringResource(R.string.flow_loop_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = loops.toFloat().coerceIn(0f, 100f),
                onValueChange = { dragging = it.roundToInt() },
                onValueChangeFinished = {
                    dragging?.let { onChange(flow.copy(loopCount = it)) }
                    dragging = null
                },
                valueRange = 0f..100f,
            )
        }
    }
}

@Composable
private fun ClipNodeRow(
    position: Int,
    node: ClipNode,
    clip: Clip?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditClip: (Clip) -> Unit,
) {
    val resources = LocalContext.current.resources
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                position.toString(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(Modifier.weight(1f)) {
                // The name opens the clip; ⚙ opens the node. Two different things on one row, so the one
                // that leaves this screen is the one that has to look like a link — and it is the name,
                // because that is the clip rather than its place in this flow.
                Text(
                    clip?.name ?: stringResource(R.string.node_clip_gone),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (clip == null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .then(if (clip == null) Modifier else Modifier.clickable { onEditClip(clip) })
                        .padding(vertical = 2.dp),
                )
                Text(
                    nodeDetail(node),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (clip != null) {
                    Text(
                        clipSummary(resources, clip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                Text("↑", style = MaterialTheme.typography.titleMedium)
            }
            IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                Text("↓", style = MaterialTheme.typography.titleMedium)
            }
            IconButton(onClick = onOpenSettings) {
                Text("⚙", style = MaterialTheme.typography.titleMedium)
            }
            IconButton(onClick = onRemove) {
                Text("−", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun nodeDetail(node: ClipNode): String = buildString {
    if (node.delayBefore > 0) {
        append(stringResource(R.string.flow_node_delay, node.delayBefore))
    }
    if (node.repeat > 1) {
        if (isNotEmpty()) append(" · ")
        append(stringResource(R.string.flow_node_repeat, node.repeat))
        if (node.repeatIntervalMs > 0) {
            append(" ")
            append(stringResource(R.string.flow_node_interval, node.repeatIntervalMs))
        }
    }
    if (isEmpty()) append(stringResource(R.string.flow_node_plain))
}

/**
 * The three knobs for one row, behind a settings button rather than shown inline.
 *
 * Inline would put three sliders on every row of a list whose job is to show the order of things.
 */
@Composable
private fun ClipNodeSettingsDialog(
    position: Int,
    node: ClipNode,
    onDismiss: () -> Unit,
    onConfirm: (ClipNode) -> Unit,
) {
    var delay by remember { mutableStateOf(node.delayBefore) }
    var repeat by remember { mutableStateOf(node.repeat) }
    var interval by remember { mutableStateOf(node.repeatIntervalMs) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.flow_node_settings_title, position)) },
        text = {
            Column {
                SliderBlock(
                    label = stringResource(R.string.param_delay),
                    value = stringResource(R.string.value_ms, delay),
                    position = delay.toFloat(),
                    range = 0f..30_000f,
                ) { delay = (it / 250f).roundToInt() * 250L }

                SliderBlock(
                    label = stringResource(R.string.param_repeat),
                    value = stringResource(R.string.value_times, repeat),
                    position = repeat.toFloat(),
                    range = 1f..50f,
                ) { repeat = it.roundToInt() }

                // Only once there is something to separate. A single pass has no gap between passes, and
                // showing the control anyway invites setting a number that does nothing.
                if (repeat > 1) {
                    SliderBlock(
                        label = stringResource(R.string.param_repeat_interval),
                        value = stringResource(R.string.value_ms, interval),
                        position = interval.toFloat(),
                        range = 0f..60_000f,
                    ) { interval = (it / 500f).roundToInt() * 500L }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    node.copy(
                        delayBefore = delay,
                        repeat = repeat,
                        repeatIntervalMs = if (repeat > 1) interval else node.repeatIntervalMs,
                    )
                )
            }) { Text(stringResource(R.string.dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

@Composable
private fun SliderBlock(
    label: String,
    value: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = position.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
        )
    }
}

@Composable
private fun PickClipDialog(clips: List<Clip>, onDismiss: () -> Unit, onPick: (Clip) -> Unit) {
    val resources = LocalContext.current.resources
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.flow_editor_add)) },
        text = {
            if (clips.isEmpty()) {
                Text(stringResource(R.string.load_empty))
            } else {
                LazyColumn(Modifier.height(320.dp)) {
                    itemsIndexed(clips) { _, clip ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp)
                        ) {
                            TextButton(onClick = { onPick(clip) }) {
                                Text(clip.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                clipSummary(resources, clip),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

// --- list helpers, kept here because nothing else needs them ---

private fun Flow.moved(index: Int, delta: Int): Flow {
    val target = index + delta
    if (target !in clips.indices) return this
    return copy(clips = clips.toMutableList().apply { add(target, removeAt(index)) })
}

private fun List<ClipNode>.minusAt(index: Int): List<ClipNode> =
    toMutableList().apply { removeAt(index) }

private fun List<ClipNode>.replacedAt(index: Int, node: ClipNode): List<ClipNode> =
    mapIndexed { position, existing -> if (position == index) node else existing }
