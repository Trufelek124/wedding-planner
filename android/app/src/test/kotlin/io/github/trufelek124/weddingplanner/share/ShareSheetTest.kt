package io.github.trufelek124.weddingplanner.share

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Where the planner appears in Android's share sheet: Android offers an app for what its manifest
 * says it takes. This asks the phone the question the share sheet asks.
 */
@RunWith(RobolectricTestRunner::class)
class ShareSheetTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun takers(action: String, type: String): List<String> =
        context.packageManager.queryIntentActivities(Intent(action).setType(type).setPackage(context.packageName), 0).map { it.activityInfo.name }

    @Test
    fun `the planner is offered for a link, which apps share as plain text`() {
        assertEquals(listOf(ShareActivity::class.java.name), takers(Intent.ACTION_SEND, "text/plain"))
    }

    @Test
    fun `and for one picture or several, of any kind`() {
        for (kind in listOf("image/jpeg", "image/png", "image/webp", "image/*")) {
            assertEquals(kind, listOf(ShareActivity::class.java.name), takers(Intent.ACTION_SEND, kind))
            assertEquals(kind, listOf(ShareActivity::class.java.name), takers(Intent.ACTION_SEND_MULTIPLE, kind))
        }
    }

    @Test
    fun `but not for what it cannot keep`() {
        assertTrue(takers(Intent.ACTION_SEND, "video/mp4").isEmpty())
        assertTrue(takers(Intent.ACTION_SEND, "application/pdf").isEmpty())
        assertTrue(takers(Intent.ACTION_SEND_MULTIPLE, "text/plain").isEmpty())
        assertTrue(takers(Intent.ACTION_VIEW, "text/plain").isEmpty())
    }
}
