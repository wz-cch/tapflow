package com.tapflow.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tapflow.android.data.Repo
import com.tapflow.android.ui.HomeScreen
import com.tapflow.android.ui.TapFlowTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        enableEdgeToEdge()
        setContent {
            TapFlowTheme {
                HomeScreen()
            }
        }
    }
}
