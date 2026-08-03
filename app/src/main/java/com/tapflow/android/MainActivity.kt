package com.tapflow.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.tapflow.android.data.Repo
import com.tapflow.android.ui.DiagnosticsScreen
import com.tapflow.android.ui.FlowEditorScreen
import com.tapflow.android.ui.HomeScreen
import com.tapflow.android.ui.SettingsScreen
import com.tapflow.android.ui.TapFlowTheme

private enum class Screen { HOME, SETTINGS, DIAGNOSTICS, FLOW }

class MainActivity : ComponentActivity() {

    /**
     * Held on the activity rather than inside setContent so [onNewIntent] can redirect an already
     * running instance. The overlay's "full settings" link would otherwise do nothing when the app
     * is merely brought to the front.
     */
    private val screen = mutableStateOf(Screen.HOME)

    /** Which flow the editor is on. Alongside [screen] for the same reason: it survives recomposition. */
    private val editingFlowId = mutableStateOf<String?>(null)

    /**
     * Whether leaving the flow editor should close the app rather than go back to the home screen.
     *
     * True when the editor was reached by *loading* — creating a flow, or the toolbar's pencil. The flow is
     * already what play runs by then, so the next thing wanted is the target app, and stopping at the home
     * screen just to close it is a step with nothing in it. False when arranging from the `⋮` menu, which
     * loaded nothing and came from the list.
     */
    private val flowEditorExits = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        applyIntent(intent)
        enableEdgeToEdge()

        setContent {
            TapFlowTheme {
                // Four screens still do not justify a navigation library, and pulling one in would mean
                // a back stack to configure for something a single enum covers.
                when (screen.value) {
                    Screen.HOME -> HomeScreen(
                        onOpenSettings = { screen.value = Screen.SETTINGS },
                        onOpenDiagnostics = { screen.value = Screen.DIAGNOSTICS },
                        onOpenFlow = { id, exitAfter ->
                            editingFlowId.value = id
                            flowEditorExits.value = exitAfter
                            screen.value = Screen.FLOW
                        },
                        // Loading hands over to the toolbar, and the toolbar is on top of another app —
                        // so the last thing loading does is get this screen out of the way.
                        onClose = { finish() },
                    )

                    Screen.SETTINGS -> {
                        BackHandler { screen.value = Screen.HOME }
                        SettingsScreen(onBack = { screen.value = Screen.HOME })
                    }

                    Screen.DIAGNOSTICS -> {
                        BackHandler { screen.value = Screen.HOME }
                        DiagnosticsScreen(onBack = { screen.value = Screen.HOME })
                    }

                    Screen.FLOW -> {
                        val id = editingFlowId.value
                        if (id == null) {
                            screen.value = Screen.HOME
                        } else {
                            // Editing a flow writes straight back, so there is never anything unsaved to
                            // ask about on the way out.
                            val leave = {
                                if (flowEditorExits.value) finish() else screen.value = Screen.HOME
                            }
                            BackHandler { leave() }
                            FlowEditorScreen(
                                flowId = id,
                                onBack = leave,
                                // The clip is already loaded and the breadcrumb set; this is the handover.
                                // Same two steps as loading from the home screen, and for the same reason:
                                // editing happens on the toolbar, on top of the app being scripted.
                                onEditClip = {
                                    Repo.setOverlayEnabled(true)
                                    finish()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    private fun applyIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true) {
            screen.value = Screen.SETTINGS
        }
        // The toolbar's pencil in flow mode. That flow is already loaded, so closing the editor should go
        // back to the target app rather than land on a home screen nobody asked for.
        intent?.getStringExtra(EXTRA_OPEN_FLOW)?.let { id ->
            editingFlowId.value = id
            flowEditorExits.value = true
            screen.value = Screen.FLOW
        }
    }

    companion object {
        const val EXTRA_OPEN_SETTINGS = "com.tapflow.android.OPEN_SETTINGS"
        const val EXTRA_OPEN_FLOW = "com.tapflow.android.OPEN_FLOW"
    }
}
