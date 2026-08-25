package com.stastyle.localsummarizer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.stastyle.localsummarizer.ui.navigation.AppNavHost
import com.stastyle.localsummarizer.ui.theme.LocalSummarizerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalSummarizerTheme {
                AppNavHost()
            }
        }
    }
}
