package com.tapflow.android.ui

import android.content.Context
import android.widget.Toast
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.tapflow.android.data.DocKind
import com.tapflow.android.data.Flow
import com.tapflow.android.data.Repo
import com.tapflow.android.data.Settings
import com.tapflow.android.engine.Session
import com.tapflow.android.text.clipSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Arranges the clips in one flow.
 *
 * A plain list, and deliberately nothing more. This screen's object is the *clip*; it cannot edit what is
 * inside one, so there are no coordinates to drag, no durations to nudge, nothing that needs the overlay.
 * If a step inside a clip needs changing, that happens where clips are edited — tap the name.
 *
 * ### Entering reads the flow
 *
 * All three ways in — arranging from the home screen, the toolbar's pencil, and coming back from editing one
 * of the clips — hand over a file reference, and this screen reads it. Unconditionally, even when that flow is
 * already open, because the one case that matters is the third: the clip that was just edited is one of the
 * files this flow points at, so re-reading is how the edit arrives. Making it conditional would mean deciding
 * *when* a re-read is needed, and the answer would be wrong exactly once.
 *
 * Every change writes straight back. A flow is a list of references with nothing accumulating in it, so there
 * is no unsaved state to protect and therefore no save button — the same reason the toolbar has none in flow
 * mode. Those writes go through [writeFile], off the main thread and reported when they fail: without that,
 * a failed write would leave the in-memory flow untouched too, and the tap would appear to do nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
/**
 * @param flowRef the flow's file. Read on entry; see above.
 * @param onEditClip one of this flow's clips has been opened for editing. Handing over to the toolbar is all
 *   that is left, and only the activity can do it.
 */
@Composable
fun FlowEditorScreen(flowRef: String, onBack: () -> Unit, onEditClip: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val open by Repo.currentFlow.collectAsStateWithLifecycle()
    var loading by remember(flowRef) { mutableStateOf(true) }

    LaunchedEffect(flowRef) {
        val opened = withContext(Dispatchers.IO) { Repo.openFlow(flowRef) }
        loading = false
        if (opened == null) {
            // The file was deleted or moved while its editor was on the way up. Nothing to arrange.
            context.toastLong(context.getString(R.string.toast_open_flow_failed))
            onBack()
        } else {
            Session.openFlow(opened)
        }
    }

    val flow = open?.takeIf { it.file.ref == flowRef }
    if (flow == null) {
        if (loading) BusyDialog()
        return
    }

    // Saveable: the picker is another activity, so a rotation while it is open would otherwise lose which
    // row the result belongs to and silently drop the choice.
    var addingAt by rememberSaveable { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf<Int?>(null) }

    /** Writes a changed node list back to the file, keeping the clips already read. */
    fun update(nodes: List<ClipNode>) {
        scope.writeFile(context) { Repo.saveFlow(flow.withNodes(nodes)) }
    }

    val clipPicker = rememberFilePicker(DocKind.CLIP) { ref ->
        val at = addingAt
        addingAt = null
        if (ref == null || at == null) return@rememberFilePicker
        scope.launch {
            // Read here rather than on the next open, so the row can show the clip's real name and summary
            // immediately — and so a file that turns out not to be a clip is refused at the moment it is
            // chosen, which is the only moment the user can do anything about it.
            val loaded = withContext(Dispatchers.IO) { Repo.openClip(ref) }
            if (loaded == null) {
                context.toastLong(context.getString(R.string.toast_open_clip_failed))
                return@launch
            }
            val node = ClipNode(ref = loaded.file.ref, name = loaded.file.name)
            // `at` is the row being repointed, or the size of the list when appending.
            val nodes = if (at in flow.flow.clips.indices) {
                flow.flow.clips.mapIndexed { index, existing ->
                    if (index == at) existing.copy(ref = node.ref, name = node.name) else existing
                }
            } else {
                flow.flow.clips + node
            }
            scope.writeFile(context) { Repo.saveFlow(flow.withNodes(nodes, loaded)) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(flow.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                LoopRow(flow.flow) { updated ->
                    scope.writeFile(context) { Repo.saveFlow(flow.withFlow(updated)) }
                }
            }

            // Above the rows, because it explains them: a reference is a location, so this is what a moved or
            // renamed clip looks like, and every one of those rows offers the one thing that fixes it.
            if (flow.missingCount > 0) {
                item {
                    Text(
                        stringResource(R.string.flow_missing_note, flow.missingCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (flow.flow.clips.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.flow_editor_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            itemsIndexed(flow.flow.clips) { index, node ->
                ClipNodeRow(
                    position = index + 1,
                    node = node,
                    name = flow.nameAt(index),
                    clip = flow.clipAt(index)?.clip,
                    canMoveUp = index > 0,
                    canMoveDown = index < flow.flow.clips.lastIndex,
                    onMove = { delta -> update(flow.flow.clips.moved(index, delta)) },
                    onRemove = { update(flow.flow.clips.minusAt(index)) },
                    onOpenSettings = { editing = index },
                    // Nothing to confirm. The flow is written on every change here, so it is already on
                    // disk, and flow mode keeps the workspace empty — so leaving for the clip cannot lose
                    // anything on either side.
                    onEditClip = {
                        flow.clipAt(index)?.let { loaded ->
                            Session.editClipFromFlow(flow.file.ref, loaded)
                            onEditClip()
                        }
                    },
                    // Opens beside the flow itself, which is where a clip that moved is most likely to be.
                    onRelink = { addingAt = index; clipPicker.open(near = flow.file.ref) },
                )
            }

            item {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { addingAt = flow.flow.clips.size; clipPicker.open(near = flow.file.ref) }
                ) {
                    Text(stringResource(R.string.flow_editor_add))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    editing?.let { index ->
        val node = flow.flow.clips.getOrNull(index)
        if (node == null) {
            editing = null
        } else {
            ClipNodeSettingsDialog(
                position = index + 1,
                node = node,
                onDismiss = { editing = null },
            ) { updated ->
                update(flow.flow.clips.replacedAt(index, updated))
                editing = null
            }
        }
    }
}

/**
 * The flow's loop count.
 *
 * Writes on release, not on every value change. Saving a flow means writing a file, which on API 29+ is a
 * ContentProvider round trip — so writing per tick would be dozens of them for one drag, and the slider would
 * stutter against its own saves. Dragging updates a local number; letting go commits it. The clip-node dialog
 * needs none of this: it already keeps its three sliders local and writes once, on its confirm button.
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

/**
 * One clip's place in the flow.
 *
 * @param clip null when the file behind this row could not be read, which is what turns the row into `!` plus
 *   a way to point it somewhere else. Not an error state to be cleared: a reference is a location, and the
 *   user is entitled to move their files.
 */
@Composable
private fun ClipNodeRow(
    position: Int,
    node: ClipNode,
    name: String,
    clip: Clip?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditClip: () -> Unit,
    onRelink: () -> Unit,
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
                    if (clip == null) {
                        stringResource(R.string.node_clip_missing, name.ifEmpty { "?" })
                    } else {
                        name
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (clip == null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable(onClick = if (clip == null) onRelink else onEditClip)
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
                } else {
                    // Under the name, where the summary would have been, because it is the answer to the
                    // question the `!` raises rather than a separate feature.
                    Text(
                        stringResource(R.string.node_relink),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onRelink),
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

// --- list helpers, kept here because nothing else needs them ---

private fun List<ClipNode>.moved(index: Int, delta: Int): List<ClipNode> {
    val target = index + delta
    if (target !in indices) return this
    return toMutableList().apply { add(target, removeAt(index)) }
}

private fun List<ClipNode>.minusAt(index: Int): List<ClipNode> =
    toMutableList().apply { removeAt(index) }

private fun List<ClipNode>.replacedAt(index: Int, node: ClipNode): List<ClipNode> =
    mapIndexed { position, existing -> if (position == index) node else existing }

private fun Context.toastLong(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
