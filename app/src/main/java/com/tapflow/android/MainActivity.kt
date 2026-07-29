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
                        onOpenFlow = { id ->
                            editingFlowId.value = id
                            screen.value = Screen.FLOW
                        },
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
                            BackHandler { screen.value = Screen.HOME }
                            FlowEditorScreen(flowId = id, onBack = { screen.value = Screen.HOME })
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
    }

    companion object {
        const val EXTRA_OPEN_SETTINGS = "com.tapflow.android.OPEN_SETTINGS"
    }
}
