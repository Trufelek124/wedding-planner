package io.github.trufelek124.weddingplanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import io.github.trufelek124.weddingplanner.ui.HomeScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val server = ServerAddress.parse(BuildConfig.SERVER_URL)
        setContent {
            MaterialTheme {
                HomeScreen(server)
            }
        }
    }
}
