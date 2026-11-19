package io.github.trufelek124.weddingplanner.planner

import io.github.trufelek124.weddingplanner.data.Vendor
import io.github.trufelek124.weddingplanner.data.VendorStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The vendors read on the phone as they do on the web's vendors pages. Amounts are in grosze, the sign after a space that does not break. */
@RunWith(RobolectricTestRunner::class)
class VendorWordsTest {

    private val pln = "PLN"
    private fun zl(amount: String) = "$amount\u00a0zł"

    private fun vendor(
        name: String = "Palace on the Hill",
        status: VendorStatus = VendorStatus.BOOKED,
        contactPerson: String = "",
        agreedPrice: Long? = null,
        committed: Long = 0,
        paid: Long = 0,
        inspirations: Int = 0,
    ) = Vendor(
        id = "v1", name = name, status = status, categoryId = null, contactPerson = contactPerson, phone = "", email = "", website = "",
        address = "", notes = "", agreedPrice = agreedPrice, committed = committed, paid = paid, inspirations = inspirations,
    )

    @Test
    fun `a status reads as a word, and a state this app does not know reads Other`() {
        assertEquals("Considering", vendorStatusLabel(VendorStatus.CONSIDERING))
        assertEquals("Booked", vendorStatusLabel(VendorStatus.BOOKED))
        assertEquals("Dropped", vendorStatusLabel(VendorStatus.DROPPED))
        assertEquals("Other", vendorStatusLabel(VendorStatus.OTHER))
    }

    @Test
    fun `the summary in More counts the vendors and the booked ones`() {
        assertEquals("No vendors yet", vendorsSummary(emptyList()))
        assertEquals("1 vendor, none booked yet", vendorsSummary(listOf(vendor(status = VendorStatus.CONSIDERING))))
        assertEquals("1 vendor, 1 booked", vendorsSummary(listOf(vendor())))
        assertEquals(
            "3 vendors, 2 booked",
            vendorsSummary(listOf(vendor(), vendor(), vendor(status = VendorStatus.DROPPED))),
        )
    }

    @Test
    fun `only the statuses that occur are offered, in the order a wedding goes through them`() {
        assertEquals(emptyList<VendorStatus>(), statusesOf(emptyList()))
        assertEquals(
            listOf(VendorStatus.CONSIDERING, VendorStatus.BOOKED, VendorStatus.OTHER),
            statusesOf(listOf(vendor(status = VendorStatus.OTHER), vendor(), vendor(status = VendorStatus.CONSIDERING), vendor())),
        )
    }

    @Test
    fun `the chosen status stays offered when no vendor has it any more`() {
        assertEquals(listOf(VendorStatus.BOOKED, VendorStatus.DROPPED), statusesOf(listOf(vendor()), chosen = VendorStatus.DROPPED))
        assertEquals(listOf(VendorStatus.BOOKED), statusesOf(listOf(vendor()), chosen = VendorStatus.BOOKED))
    }

    @Test
    fun `a status narrows the list down, and a dropped vendor is listed after the others`() {
        val dropped = vendor(name = "Dropped one", status = VendorStatus.DROPPED)
        val booked = vendor(name = "Booked one")
        val considering = vendor(name = "Considering one", status = VendorStatus.CONSIDERING)
        val all = listOf(dropped, booked, considering)

        assertEquals(listOf("Booked one", "Considering one", "Dropped one"), vendorsShown(all, null).map { it.name })
        assertEquals(listOf("Booked one"), vendorsShown(all, VendorStatus.BOOKED).map { it.name })
        assertEquals(listOf("Dropped one"), vendorsShown(all, VendorStatus.DROPPED).map { it.name })
        assertTrue(vendorsShown(all, VendorStatus.OTHER).isEmpty())
    }

    @Test
    fun `a line of the list says the money only where there is any`() {
        assertEquals(emptyList<String>(), vendorMoneyFacts(vendor(), pln))
        assertEquals(listOf(zl("1,000.00") + " agreed", zl("0.00") + " paid"), vendorMoneyFacts(vendor(agreedPrice = 100000), pln))
        // Payments without a price: only what is paid.
        assertEquals(listOf(zl("300.00") + " paid"), vendorMoneyFacts(vendor(committed = 50000, paid = 30000), pln))
    }

    @Test
    fun `a line is one statement for a screen reader`() {
        assertEquals(
            "Palace on the Hill. Booked. Venue. Jan. ${zl("1,000.00")} agreed. ${zl("300.00")} paid",
            vendorLineInWords(vendor(contactPerson = "Jan", agreedPrice = 100000, committed = 100000, paid = 30000), "Venue", pln),
        )
        assertEquals("Palace on the Hill. Booked", vendorLineInWords(vendor(), null, pln))
    }

    @Test
    fun `what was agreed, and what is paid and still to pay`() {
        assertEquals("No price agreed.", agreedInWords(null, pln))
        assertEquals("${zl("1,000.00")} agreed.", agreedInWords(100000, pln))
        assertEquals("${zl("300.00")} paid, ${zl("700.00")} still to pay.", paidSentence(30000, 100000, pln))
        assertEquals("${zl("1,000.00")} paid.", paidSentence(100000, 100000, pln))
        assertEquals("${zl("0.00")} paid.", paidSentence(0, 0, pln))
    }

    @Test
    fun `the payments are held against the agreed price in words, over it first`() {
        assertEquals("The payments come to ${zl("200.00")} more than was agreed.", againstAgreed(100000, 120000, pln))
        assertTrue(overAgreed(100000, 120000))
        assertEquals("${zl("250.00")} of the agreed price has no payment yet.", againstAgreed(100000, 75000, pln))
        assertFalse(overAgreed(100000, 75000))
        assertNull(againstAgreed(100000, 100000, pln))
        assertNull(againstAgreed(null, 75000, pln))
        assertFalse(overAgreed(null, 75000))
    }

    @Test
    fun `there is money to tell of with a price or a payment`() {
        assertFalse(hasVendorMoney(vendor()))
        assertTrue(hasVendorMoney(vendor(agreedPrice = 0)))
        assertTrue(hasVendorMoney(vendor(committed = 1)))
    }

    @Test
    fun `inspirations are counted only where there are some`() {
        assertNull(inspirationsInWords(0))
        assertEquals("1 inspiration", inspirationsInWords(1))
        assertEquals("3 inspirations", inspirationsInWords(3))
    }

    @Test
    fun `the rows that open an app say what they do`() {
        assertEquals("Call +48 600 000 001", callInWords("+48 600 000 001"))
        assertEquals("Write an e-mail to jan@palace.example", mailInWords("jan@palace.example"))
        assertEquals("Show the address on a map: Hill Road 1, Gdansk", mapInWords("Hill Road 1,\n Gdansk"))
        assertEquals("Open the website palace.example", siteInWords("palace.example"))
    }
}
