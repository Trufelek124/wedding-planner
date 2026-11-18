package io.github.trufelek124.weddingplanner.ui

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage

/** A phone held upright, as Robolectric is told to be one: 360 × 780 dp. */
const val PHONE = "w360dp-h780dp-xhdpi"

/** The same phone with the dark theme on. */
const val PHONE_AT_NIGHT = "w360dp-h780dp-night-xhdpi"

/**
 * Writes what the screen shows to `app/build/outputs/roborazzi/<name>.png`. There is no emulator
 * where this project is built, so this is how a screen is looked at: whoever changes one opens its
 * picture. Nothing is compared with a stored picture, and no picture is kept in the repository.
 *
 * The test class needs `@GraphicsMode(GraphicsMode.Mode.NATIVE)`, or the picture is empty.
 */
fun ComposeContentTestRule.picture(name: String) {
    onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
}
