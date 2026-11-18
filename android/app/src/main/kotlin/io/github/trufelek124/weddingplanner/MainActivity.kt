package io.github.trufelek124.weddingplanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.trufelek124.weddingplanner.ui.AppRoot
import io.github.trufelek124.weddingplanner.ui.PlannerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as WeddingPlannerApp).container
        setContent {
            PlannerTheme {
                AppRoot(container)
            }
        }
    }
}
