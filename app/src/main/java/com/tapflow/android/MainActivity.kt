package com.tapflow.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tapflow.android.data.Repo
import com.tapflow.android.ui.HomeScreen
import com.tapflow.android.ui.SettingsScreen
import com.tapflow.android.ui.TapFlowTheme

private enum class Screen { HOME, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        enableEdgeToEdge()
        setContent {
            TapFlowTheme {
                // Two screens do not justify a navigation library, and pulling one in would mean a
                // back stack to configure for something a single enum covers.
                var screen by remember { mutableStateOf(Screen.HOME) }

                when (screen) {
                    Screen.HOME -> HomeScreen(onOpenSettings = { screen = Screen.SETTINGS })
                    Screen.SETTINGS -> {
                        BackHandler { screen = Screen.HOME }
                        SettingsScreen(onBack = { screen = Screen.HOME })
                    }
                }
            }
        }
    }
}
