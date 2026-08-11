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
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tapflow.android.R
import com.tapflow.android.data.Clip
import com.tapflow.android.data.ClipNode
import com.tapflow.android.data.DocFile
import com.tapflow.android.data.DocKind
import com.tapflow.android.data.DocStore
import com.tapflow.android.data.Flow
import com.tapflow.android.data.LoadedClip
import com.tapflow.android.data.OpenFlow
import com.tapflow.android.data.Repo
import com.tapflow.android.data.Settings
import com.tapflow.android.engine.Session
import com.tapflow.android.text.clipSummary
import com.tapflow.android.text.defaultFlowName
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
 * The ways in that name a flow — arranging from the home screen, the toolbar's pencil, coming back from
 * editing one of the clips — hand over a file reference, and this screen reads it. Unconditionally, even when
 * that flow is already open, because the one case that matters is the last: the clip that was just edited is
 * one of the files this flow points at, so re-reading is how the edit arrives. Making it conditional would
 * mean deciding *when* a re-read is needed, and the answer would be wrong exactly once.
 *
 * ### It is arranged in memory, and named when it is saved
 *
 * **This used to write straight back on every tap, and a new flow was a file before it was an arrangement.**
 * Which made flows the opposite of clips: a clip is recorded and then named, a flow had to be named before it
 * could be started. Two costs came out of that — the order was inconsistent for no reason anyone could give,
 * and every abandoned experiment left an empty `.flow` behind.
 *
 * So the arrangement lives here until it is saved, and leaving with changes asks. **The unsaved window is
 * exactly this screen**, which is what keeps the rest of the app as simple as it was: outside the editor a
 * flow is still always saved, so flow mode still needs no `💾`, and the five places that ask about discarding
 * a workspace still only have to think about clips.
 *
 * There is no draft behind it, deliberately. The clip workspace has one because its unsaved state outlives
 * any screen; this one does not, and a draft would have to answer "does the draft or the file win" against
 * the re-read rule above — a question whose wrong answer is silent.
 */
@OptIn(ExperimentalMaterial3Api::class)
/**
 * @param flowRef the flow's file, or null to arrange one that does not exist yet. Read on entry; see above.
 * @param onEditClip one of this flow's clips has been opened for editing. Handing over to the toolbar is all
 *   that is left, and only the activity can do it.
 */
@Composable
fun FlowEditorScreen(flowRef: String?, onBack: () -> Unit, onEditClip: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The arrangement being worked on, held here rather than written through on every tap. A new flow has no
    // file until it is saved, which is what makes "make it, then name it" possible — the same order a clip
    // has always had. Keyed on the ref so arriving at a different flow starts clean.
    var file by remember(flowRef) { mutableStateOf<DocFile?>(null) }
    var working by remember(flowRef) { mutableStateOf(Flow(clips = emptyList())) }
    var resolved by remember(flowRef) { mutableStateOf<Map<String, LoadedClip>>(emptyMap()) }
    var dirty by remember(flowRef) { mutableStateOf(false) }
    var loading by remember(flowRef) { mutableStateOf(flowRef != null) }
    var ready by remember(flowRef) { mutableStateOf(flowRef == null) }

    LaunchedEffect(flowRef) {
        if (flowRef == null) return@LaunchedEffect
        val opened = withContext(Dispatchers.IO) { Repo.openFlow(flowRef) }
        loading = false
        if (opened == null) {
            // The file was deleted or moved while its editor was on the way up. Nothing to arrange.
            context.toastLong(context.getString(R.string.toast_open_flow_failed))
            onBack()
        } else {
            file = opened.file
            working = opened.flow
            resolved = opened.clips.mapValues { LoadedClip(DocFile(it.key, DocStore.label(it.key)), it.value) }
            Session.openFlow(opened)
            ready = true
        }
    }

    if (!ready) {
        // Cancellable: there is nothing else on screen yet, so a slow read would otherwise be a spinner with
        // no exit — the dialog swallows back before the editor's own handler sees it.
        if (loading) BusyDialog(onCancel = onBack)
        return
    }

    val nodes = working.clips

    // Three lookups over the clips read so far. They live here rather than on a shared type because what is
    // being arranged is not an OpenFlow — it may have no file yet — and one nullable field would have made
    // every saved-flow caller in the app handle a case that cannot reach them.
    fun nameAt(index: Int): String {
        val node = nodes.getOrNull(index) ?: return ""
        return resolved[node.ref]?.file?.name ?: node.name
    }

    fun clipAt(index: Int): LoadedClip? = nodes.getOrNull(index)?.let { resolved[it.ref] }
    val missingCount = nodes.count { it.ref !in resolved }

    // Saveable: the picker is another activity, so a rotation while it is open would otherwise lose which
    // row the result belongs to and silently drop the choice.
    var addingAt by rememberSaveable { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf<Int?>(null) }

    /** Non-null while the unsaved-changes question is on screen; holds what that question is blocking. */
    var asking by remember { mutableStateOf<(() -> Unit)?>(null) }

    /**
     * What to do once a name has been chosen and the write has landed.
     *
     * Deliberately not the same field as [asking]. They overlapped in the first version of this, and the
     * result was the question reappearing on top of the name picker it had just opened — one field cannot
     * mean both "a question is up" and "something is waiting behind a picker".
     */
    var afterSave by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun update(next: List<ClipNode>) {
        working = working.copy(clips = next)
        dirty = true
    }

    fun writeTo(target: DocFile, andThen: () -> Unit) {
        scope.launch {
            val opened = OpenFlow(target, working, resolved)
            val ok = withContext(Dispatchers.IO) { Repo.saveFlow(opened) }
            if (!ok) {
                context.toastLong(context.getString(R.string.toast_save_failed))
                return@launch
            }
            file = target
            dirty = false
            // Makes it the loaded flow, so flow mode's play button runs what was just written rather than
            // whatever was open before.
            Session.openFlow(opened)
            andThen()
        }
    }

    // Save-only: the name field is the point, and tapping an existing flow fills the name in rather than
    // opening it — opening from here would throw away the arrangement you came to name.
    val namePicker = rememberFilePicker(DocKind.FLOW) { picked ->
        val ref = (picked as? Picked.Save)?.ref
        val after = afterSave
        afterSave = null
        if (ref == null) return@rememberFilePicker
        writeTo(DocFile(ref, DocStore.label(ref))) { after?.invoke() }
    }

    /** Saves, asking for a name first when this flow has never had one. */
    fun save(andThen: () -> Unit) {
        val target = file
        if (target == null) {
            afterSave = andThen
            namePicker.save(defaultFlowName(context.resources, System.currentTimeMillis()))
        } else {
            writeTo(target, andThen)
        }
    }

    /** Runs [action] once the arrangement is safe to leave behind. */
    fun guarded(action: () -> Unit) {
        if (dirty) asking = action else action()
    }

    fun leave() = guarded(onBack)

    // The editor's own back, so the system gesture and the arrow ask the same question. Registered before
    // the dialogs below, which put their own handlers on top while they are up.
    BackHandler { leave() }

    // Open-only. Writing a clip from here would mean creating an empty one, and a flow row pointing at an
    // empty clip is a row that does nothing — clips come from recording.
    val clipPicker = rememberFilePicker(DocKind.CLIP) { picked ->
        val at = addingAt
        addingAt = null
        val ref = (picked as? Picked.Open)?.ref
        if (ref == null || at == null) return@rememberFilePicker
        scope.launch {
            // Read here rather than on the next open, so the row can show the clip's real name and summary
            // immediately — and so a file that turns out not to be a clip is refused at the moment it is
            // chosen, which is the only moment the user can do anything about it.
            val loaded = withContext(Dispatchers.IO) { Repo.openClip(ref) }
            if (loaded == null) {
                // Only reachable for a real `.clip` that will not parse: the picker refuses anything else,
                // and says so itself.
                context.toastLong(context.getString(R.string.toast_open_clip_failed))
                return@launch
            }
            val node = ClipNode(ref = loaded.file.ref, name = loaded.file.name)
            resolved = resolved + (loaded.file.ref to loaded)
            // `at` is the row being repointed, or the size of the list when appending.
            update(
                if (at in nodes.indices) {
                    nodes.mapIndexed { index, existing ->
                        if (index == at) existing.copy(ref = node.ref, name = node.name) else existing
                    }
                } else {
                    nodes + node
                }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        file?.name ?: stringResource(R.string.flow_untitled),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    // Enabled only when there is something to write. A save button that is always live
                    // invites pressing it to find out whether it was needed.
                    TextButton(onClick = { save {} }, enabled = dirty) {
                        Text(stringResource(R.string.flow_action_save))
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
                LoopRow(working) { updated ->
                    working = updated
                    dirty = true
                }
            }

            // Above the rows, because it explains them: a reference is a location, so this is what a moved or
            // renamed clip looks like, and every one of those rows offers the one thing that fixes it.
            if (missingCount > 0) {
                item {
                    Text(
                        stringResource(R.string.flow_missing_note, missingCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (nodes.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.flow_editor_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            itemsIndexed(nodes) { index, node ->
                ClipNodeRow(
                    position = index + 1,
                    node = node,
                    name = nameAt(index),
                    clip = clipAt(index)?.clip,
                    canMoveUp = index > 0,
                    canMoveDown = index < nodes.lastIndex,
                    onMove = { delta -> update(nodes.moved(index, delta)) },
                    onRemove = { update(nodes.minusAt(index)) },
                    onOpenSettings = { editing = index },
                    // Guarded, and this is where the in-memory arrangement earns the question: going to the
                    // clip leaves this screen, and the way back is a reference to a *file*. An arrangement
                    // that has never been written has no file to come back to.
                    // Saves first rather than offering to discard, and that is the one place the two
                    // questions differ: leaving for the clip means coming *back*, and the way back is a
                    // reference to a file. An arrangement that was thrown away has nowhere to return to.
                    onEditClip = {
                        val loaded = clipAt(index) ?: return@ClipNodeRow
                        val handOver = {
                            file?.ref?.let { ref ->
                                Session.editClipFromFlow(ref, loaded)
                                onEditClip()
                            }
                            Unit
                        }
                        if (file == null || dirty) save(handOver) else handOver()
                    },
                    // Opens beside the flow itself, which is where a clip that moved is most likely to be.
                    onRelink = { addingAt = index; clipPicker.open(near = file?.ref) },
                )
            }

            item {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { addingAt = nodes.size; clipPicker.open(near = file?.ref) }) {
                    Text(stringResource(R.string.flow_editor_add))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // Three answers, and the third is the reason this is not an ordinary two-button dialog: leaving without
    // saving and cancelling are different intentions, and a dialog that offers only "OK" for one of them
    // makes the other unreachable except by guessing that the outside is safe to tap.
    asking?.let { action ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.flow_leave_title)) },
            confirmButton = {
                Row {
                    TextButton(onClick = { asking = null; action() }) {
                        Text(stringResource(R.string.flow_leave_discard))
                    }
                    TextButton(onClick = { asking = null; save(action) }) {
                        Text(stringResource(R.string.flow_leave_save))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { asking = null }) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }

    editing?.let { index ->
        val node = nodes.getOrNull(index)
        if (node == null) {
            editing = null
        } else {
            ClipNodeSettingsDialog(
                position = index + 1,
                node = node,
                onDismiss = { editing = null },
            ) { updated ->
                update(nodes.replacedAt(index, updated))
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
    // The whole card opens the clip — or repoints it, when there is no clip behind the row to open.
    //
    // It used to be the name and nothing else, which on a real device is a line of text a few characters
    // wide and the only way in. The four buttons on the right keep their own hit areas and win over this
    // one, so nothing became harder to reach; what changed is that the *common* action stopped being the
    // smallest target on the row. Same gesture as the storage panel and the home screen: tap the row, get
    // the thing the row is about.
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = if (clip == null) onRelink else onEditClip)
    ) {
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
                // Still coloured like a link, though the whole card now carries the tap: the colour is what
                // says this row leads somewhere, and the `⚙` beside it leads somewhere else.
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
                    modifier = Modifier.padding(vertical = 2.dp),
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
                    // question the `!` raises rather than a separate feature. No tap of its own now — the
                    // card carries it — so this is a label saying what tapping does.
                    Text(
                        stringResource(R.string.node_relink),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
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
    if (node.repeatsForTime) {
        if (isNotEmpty()) append(" · ")
        append(stringResource(R.string.flow_node_repeat_for, node.repeatForMs / 1000L))
        if (node.repeatIntervalMs > 0) {
            append(" ")
            append(stringResource(R.string.flow_node_interval, node.repeatIntervalMs))
        }
    } else if (node.repeat > 1) {
        if (isNotEmpty()) append(" · ")
        append(stringResource(R.string.flow_node_repeat, node.repeat))
        if (node.repeatIntervalMs > 0) {
            append(" ")
            append(stringResource(R.string.flow_node_interval, node.repeatIntervalMs))
        }
    }
    if (isEmpty()) append(stringResource(R.string.flow_node_plain))
}

/** As far as the repeat slider goes. The number itself goes to [Settings.MAX_REPEAT] — see the row. */
private const val SLIDER_MAX_REPEAT = 50f

/** Five minutes of dragging range; the number goes to [MAX_REPEAT_FOR_SECONDS]. */
private const val SLIDER_MAX_REPEAT_SECONDS = 300f

/** An hour. Long enough for any real use, short enough that a mistyped digit is obvious. */
private const val MAX_REPEAT_FOR_SECONDS = 3600

/** What a clip on a clock gets when it has no gap of its own. Long enough to be a gap, short enough to
 *  not be a decision anyone has to notice. */
private const val FORCED_INTERVAL_MS = 500L

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
    var forSeconds by remember { mutableStateOf((node.repeatForMs / 1000L).toInt()) }
    var interval by remember { mutableStateOf(node.repeatIntervalMs) }
    var typingRepeat by remember { mutableStateOf(false) }
    var typingFor by remember { mutableStateOf(false) }

    val onClock = forSeconds > 0
    // The interval is compulsory once this clip is on a clock, and the same rule the step level follows for
    // the same reason: a count with no gap fires its passes back to back, which is noisy but ends, while a
    // *duration* with no gap is half an hour of one clip with nothing between the passes. A clip of a single
    // tap makes the two identical, which is why the rule is not softened here.
    val effectiveInterval = if (onClock) interval.coerceAtLeast(FORCED_INTERVAL_MS) else interval

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

                // The slider stops at 50 and the number does not. Dragging is for the counts anyone reaches
                // for — two, three, ten — and a slider stretched to 999 makes every one of those a pixel
                // apart. The same split the loop count above already uses, and now the same ceiling the
                // step level uses, so "repeat 200 times" means one thing wherever it is asked for.
                SliderBlock(
                    label = stringResource(R.string.param_repeat),
                    value = if (onClock) {
                        stringResource(R.string.param_unset)
                    } else {
                        stringResource(R.string.value_times, repeat)
                    },
                    position = repeat.toFloat(),
                    range = 1f..SLIDER_MAX_REPEAT,
                    onTypeIn = { typingRepeat = true },
                ) { repeat = it.roundToInt(); forSeconds = 0 }

                // The other way to say how much: for this long, rather than this many times. Two rows with a
                // dash on the one not in force, rather than a switch — a switch has to hide the number it is
                // not showing, and which of the two is running this clip is the thing worth being able to
                // read without touching anything.
                SliderBlock(
                    label = stringResource(R.string.param_repeat_for),
                    value = if (onClock) {
                        stringResource(R.string.value_seconds, forSeconds)
                    } else {
                        stringResource(R.string.param_unset)
                    },
                    position = forSeconds.toFloat(),
                    range = 0f..SLIDER_MAX_REPEAT_SECONDS,
                    onTypeIn = { typingFor = true },
                ) { forSeconds = it.roundToInt(); if (forSeconds > 0) repeat = 1 }

                // Only once there is something to separate. A single pass has no gap between passes, and
                // showing the control anyway invites setting a number that does nothing.
                if (repeat > 1 || onClock) {
                    SliderBlock(
                        label = stringResource(R.string.param_repeat_interval),
                        value = stringResource(R.string.value_ms, effectiveInterval),
                        position = effectiveInterval.toFloat(),
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
                        // One of the two, never both: a node holding a count *and* a clock would need a
                        // third field to say which wins, which is exactly what "0 means not this" avoids.
                        repeat = if (onClock) 1 else repeat,
                        repeatForMs = forSeconds * 1000L,
                        repeatIntervalMs = if (repeat > 1 || onClock) {
                            effectiveInterval
                        } else {
                            node.repeatIntervalMs
                        },
                    )
                )
            }) { Text(stringResource(R.string.dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )

    // Over the settings dialog rather than replacing it, so the two numbers you did not come to change stay
    // on screen and confirming is still one button away.
    if (typingRepeat) {
        NumberEntryDialog(
            title = stringResource(R.string.param_repeat),
            entry = TypedNumber(repeat, 1..Settings.MAX_REPEAT) { repeat = it; forSeconds = 0 },
        ) { typingRepeat = false }
    }

    if (typingFor) {
        NumberEntryDialog(
            title = stringResource(R.string.repeat_for_pad_title),
            // Zero is the way back to counting, so the range starts there rather than at one.
            entry = TypedNumber(forSeconds, 0..MAX_REPEAT_FOR_SECONDS) { entered ->
                forSeconds = entered
                if (entered > 0) repeat = 1
            },
        ) { typingFor = false }
    }
}

/**
 * @param onTypeIn non-null when the value may be typed as well as dragged, which makes it the tappable
 *   link the loop count above uses. Rows without one are ranges a slider can address on its own.
 */
@Composable
private fun SliderBlock(
    label: String,
    value: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    onTypeIn: (() -> Unit)? = null,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (onTypeIn == null) Color.Unspecified else MaterialTheme.colorScheme.primary,
                modifier = if (onTypeIn == null) {
                    Modifier
                } else {
                    Modifier
                        .clickable(onClick = onTypeIn)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                },
            )
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
