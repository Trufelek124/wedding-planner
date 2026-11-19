package io.github.trufelek124.weddingplanner.planner

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** What a contact detail starts. What a person typed is not trusted: a website opens only as http or https. */
@RunWith(RobolectricTestRunner::class)
class ContactTest {

    @Test
    fun `a phone number is dialled, not called, with only its digits and a leading plus`() {
        val intent = callIntent("+48 600 000 001")!!

        assertEquals(Intent.ACTION_DIAL, intent.action)
        assertEquals("tel:+48600000001", intent.data.toString())
        assertEquals("tel:600000001", callIntent("(600) 000-001")!!.data.toString())
        assertNull(callIntent("ask at the desk"))
    }

    @Test
    fun `only the first number of what was typed is dialled, without what follows it`() {
        assertEquals("tel:600000001", callIntent("600 000 001 ext. 5")!!.data.toString())
        assertEquals("tel:+48600000001", callIntent("+48 600 000 001 (after 5 pm)")!!.data.toString())
        assertEquals("tel:600000001", callIntent("600 000 001, 600 000 002")!!.data.toString())
        assertEquals("tel:600000001", callIntent("600 000 001 / 600 000 002")!!.data.toString())
        assertEquals("tel:600000001", callIntent("Jan: 600.000.001")!!.data.toString())
        assertEquals("tel:+48600000001", callIntent("+48 600 000 001 +48 600 000 002")!!.data.toString())
        assertNull(callIntent("+ (ask)"))
        assertEquals("tel:600000001", callIntent("600\u00a0000\u00a0001")!!.data.toString())
    }

    @Test
    fun `an e-mail address opens the mail app`() {
        val intent = mailIntent("jan@palace.example")!!

        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto:jan@palace.example", intent.data.toString())
        assertNull(mailIntent("  "))
    }

    @Test
    fun `an address is looked up on a map, encoded`() {
        val intent = mapIntent("Hill Road 1, 80-000 Gdansk")!!

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("geo:0,0?q=Hill%20Road%201%2C%2080-000%20Gdansk", intent.data.toString())
        assertNull(mapIntent(""))
    }

    @Test
    fun `a website opens as typed when it is http or https, and a bare one gets https in front`() {
        assertEquals("https://www.palace.example/weddings", websiteAddress("https://www.palace.example/weddings"))
        assertEquals("http://palace.example", websiteAddress("http://palace.example"))
        assertEquals("https://palace.example", websiteAddress("  palace.example "))
        assertEquals("https://palace.example/weddings?x=1", websiteAddress("palace.example/weddings?x=1"))
        assertEquals("https://palace.example:8080/x", websiteAddress("palace.example:8080/x"))
        assertEquals("https://palace.example", websiteAddress("HTTPS://palace.example"))
        val intent = siteIntent("palace.example")!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://palace.example", intent.data.toString())
    }

    @Test
    fun `a website with any other scheme, or none that can be read, opens nothing`() {
        for (typed in listOf(
            "javascript:alert(1)", "JavaScript:alert(1)", "intent:#Intent;scheme=https;end", "file:///sdcard/x", "content://contacts/people",
            "mailto:jan@palace.example", "tel:+48600000001", "http:palace.example", "ftp://palace.example", "//palace.example",
            "https://", "", "   ", "palace example", "https://palace.example/a b",
        )) {
            assertNull("\"$typed\" should open nothing", websiteAddress(typed))
            assertNull(siteIntent(typed))
        }
    }

    @Test
    fun `a website is called by its host, and one that cannot open is called as typed`() {
        assertEquals("palace.example", siteName("https://www.palace.example/weddings"))
        assertEquals("palace.example", siteName("palace.example"))
        assertEquals("javascript:alert(1)", siteName("javascript:alert(1)"))
    }

    @Test
    fun `starting an intent that no app takes says no and does not crash`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val intent = callIntent("+48 600 000 001")!!

        assertTrue(startOrSayNo(application, intent))
        assertEquals("tel:+48600000001", shadowOf(application).nextStartedActivity.data.toString())

        shadowOf(application).checkActivities(true)
        assertFalse(startOrSayNo(application, Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://palace.example"))))
        assertNull(shadowOf(application).nextStartedActivity)
    }
}
