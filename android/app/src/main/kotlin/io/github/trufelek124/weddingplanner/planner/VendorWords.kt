package io.github.trufelek124.weddingplanner.planner

import io.github.trufelek124.weddingplanner.data.Vendor
import io.github.trufelek124.weddingplanner.data.VendorStatus
import io.github.trufelek124.weddingplanner.money.money

/*
 * How the vendors read on the phone: the same words as the web's vendors pages
 * (web/src/vendors/VendorsPage.tsx, VendorPage.tsx). Each function says what one place on the
 * screen says, so that the words are tested without a screen.
 */

/** "Booked". A state a newer server knows and this app does not reads "Other". */
fun vendorStatusLabel(status: VendorStatus): String = when (status) {
    VendorStatus.CONSIDERING -> "Considering"
    VendorStatus.BOOKED -> "Booked"
    VendorStatus.DROPPED -> "Dropped"
    VendorStatus.OTHER -> "Other"
}

/** "1 vendor", "3 vendors" */
fun vendorCount(vendors: Int): String = if (vendors == 1) "1 vendor" else "$vendors vendors"

/** The one line under "Vendors" in the list of what More leads to. */
fun vendorsSummary(vendors: List<Vendor>): String {
    if (vendors.isEmpty()) return "No vendors yet"
    val booked = vendors.count { it.status == VendorStatus.BOOKED }
    return "${vendorCount(vendors.size)}, " + if (booked == 0) "none booked yet" else "$booked booked"
}

/**
 * The statuses to offer: those that occur among the vendors, in the order a wedding goes through
 * them. The one that is chosen stays offered when its last vendor is gone (after a load), so that
 * the list can say that nothing matches instead of changing what was asked for.
 */
fun statusesOf(vendors: List<Vendor>, chosen: VendorStatus? = null): List<VendorStatus> =
    VendorStatus.entries.filter { status -> status == chosen || vendors.any { it.status == status } }

/** What the list shows: one status or all, and a vendor that was dropped stays in the list, after the others. */
fun vendorsShown(vendors: List<Vendor>, only: VendorStatus?): List<Vendor> =
    vendors.filter { only == null || it.status == only }.sortedBy { it.status == VendorStatus.DROPPED }

/** "2 inspirations", or null where there is none. */
fun inspirationsInWords(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 inspiration"
    else -> "$count inspirations"
}

/** The money in a line of the list: what was agreed, where there is a price, and what is paid; nothing where there is neither. */
fun vendorMoneyFacts(vendor: Vendor, currency: String): List<String> =
    if (vendor.agreedPrice == null && vendor.committed <= 0) {
        emptyList()
    } else {
        listOfNotNull(vendor.agreedPrice?.let { "${money(it, currency)} agreed" }, "${money(vendor.paid, currency)} paid")
    }

/** "No price agreed." or "1,000.00 zł agreed." */
fun agreedInWords(agreedPrice: Long?, currency: String): String =
    if (agreedPrice == null) "No price agreed." else "${money(agreedPrice, currency)} agreed."

/** "300.00 zł paid, 700.00 zł still to pay." — what is still to pay is left out when nothing is. */
fun paidSentence(paid: Long, committed: Long, currency: String): String {
    val toPay = committed - paid
    return if (toPay > 0) "${money(paid, currency)} paid, ${money(toPay, currency)} still to pay." else "${money(paid, currency)} paid."
}

/** Whether the payments come to more than was agreed. */
fun overAgreed(agreedPrice: Long?, committed: Long): Boolean = agreedPrice != null && committed > agreedPrice

/** Where the payments stand against the price: null where they match it or no price was agreed. */
fun againstAgreed(agreedPrice: Long?, committed: Long, currency: String): String? = when {
    agreedPrice == null || committed == agreedPrice -> null
    committed > agreedPrice -> "The payments come to ${money(committed - agreedPrice, currency)} more than was agreed."
    else -> "${money(agreedPrice - committed, currency)} of the agreed price has no payment yet."
}

/** Whether there is any money to tell of: a price, or a payment. */
fun hasVendorMoney(vendor: Vendor): Boolean = vendor.agreedPrice != null || vendor.committed > 0

/** "Palace on the Hill. Booked. Venue. Contact person Jan. 1,000.00 zł agreed. …": a line of the list as one statement. */
fun vendorLineInWords(vendor: Vendor, category: String?, currency: String): String =
    (listOfNotNull(vendor.name, vendorStatusLabel(vendor.status), category, vendor.contactPerson.takeIf { it.isNotEmpty() }) + vendorMoneyFacts(vendor, currency))
        .joinToString(". ")

/** What a screen reader is told for the row that dials. */
fun callInWords(phone: String): String = "Call $phone"

fun mailInWords(email: String): String = "Write an e-mail to $email"

fun mapInWords(address: String): String = "Show the address on a map: ${address.replace(Regex("\\s+"), " ")}"

fun siteInWords(site: String): String = "Open the website $site"
