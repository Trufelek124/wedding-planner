package io.github.trufelek124.weddingplanner

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.trufelek124.weddingplanner.reminders.OpenAt
import io.github.trufelek124.weddingplanner.ui.AppRoot
import io.github.trufelek124.weddingplanner.ui.PlannerTheme

class MainActivity : ComponentActivity() {

    /** Where the intent that started the app, or came to it while it was open, asks to go: a reminder asks for the tasks. */
    private var opening by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as WeddingPlannerApp).container
        // A turn of the phone starts the activity again with the same intent: what was opened is not opened a second time.
        if (savedInstanceState == null) {
            opening = intent.getStringExtra(OpenAt.EXTRA)
        }
        setContent {
            PlannerTheme {
                AppRoot(container, opening = opening, onOpened = { opening = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        opening = intent.getStringExtra(OpenAt.EXTRA)
    }
}
