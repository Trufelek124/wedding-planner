package io.github.trufelek124.weddingplanner.planner

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/*
 * Contact details open the phone's own apps: the dialler, the mail app, a map and the browser.
 * What the server stores was typed by a person, so it is untrusted: each way builds an intent of
 * one fixed kind, and a website opens only as an http or https address.
 *
 * A phone number is only dialled (the person presses call), so no permission is needed.
 */

/**
 * The number as the dialler wants it: digits, and a plus at the start. Null where nothing dialable is left.
 *
 * Only the first number in what was typed is taken, up to whatever is not part of a number:
 * "600 000 001 ext. 5" and "600 000 001, after five" dial 600 000 001, not a number with more digits.
 */
fun dialNumber(phone: String): String? {
    val from = phone.indexOfFirst { it == '+' || it in '0'..'9' }
    if (from < 0) return null
    val digits = phone.substring(from).filterIndexed { at, _ -> at > 0 || phone[from] != '+' }
        .takeWhile { it in '0'..'9' || it in NUMBER_PUNCTUATION }
        .filter { it in '0'..'9' }
    if (digits.isEmpty()) return null
    return if (phone[from] == '+') "+$digits" else digits
}

/** What people write between the digits of one number. A comma, a slash, a second plus or a word begins something else. */
private const val NUMBER_PUNCTUATION = " ()-.\u00a0"

private val HAS_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
private val IS_A_PORT = Regex("^[A-Za-z0-9.-]+:[0-9]+([/?#].*)?$")

/**
 * The address a website opens at: an http or https address as typed, or a bare "palace.example"
 * with https put in front. Null for anything else (javascript:, intent:, file:, content:, a
 * mail or tel address): that is shown as text and opens nothing.
 */
fun websiteAddress(website: String): String? {
    val typed = website.trim()
    if (typed.isEmpty() || typed.any { it.isWhitespace() || it.isISOControl() }) return null
    val address = when {
        typed.startsWith("//") -> return null
        HAS_SCHEME.containsMatchIn(typed) && !IS_A_PORT.matches(typed) -> {
            val scheme = typed.substringBefore(':').lowercase()
            // Only a "//" after the scheme makes it a web address: "http:evil" is not one.
            if ((scheme != "http" && scheme != "https") || !typed.substring(scheme.length + 1).startsWith("//")) return null
            scheme + typed.substring(scheme.length)
        }
        else -> "https://$typed"
    }
    val host = Uri.parse(address).host
    return address.takeIf { !host.isNullOrBlank() }
}

/** "palace.example" for "https://www.palace.example/weddings": what a website is called. */
fun siteName(website: String): String {
    val host = websiteAddress(website)?.let { Uri.parse(it).host } ?: return website
    return host.removePrefix("www.")
}

// Only digits and a plus are left of a number, so nothing in it can be taken for anything else.
fun callIntent(phone: String): Intent? = dialNumber(phone)?.let { Intent(Intent.ACTION_DIAL, Uri.parse("tel:$it")) }

fun mailIntent(email: String): Intent? =
    email.trim().takeIf { it.isNotEmpty() }?.let { Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(it, "@+"))) }

fun mapIntent(address: String): Intent? =
    address.trim().takeIf { it.isNotEmpty() }?.let { Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(it))) }

/** The one host a message of ours is opened at. */
private const val MAIL_HOST = "mail.google.com"

/**
 * The address a suggestion's message opens at: an https address whose host is exactly mail.google.com, as the server
 * made it. Null for anything else (http, another host, a host that only starts or ends like it, a user part before the
 * host, a port, javascript:, intent:): such a link is not opened and draws no button. The link comes out of what the
 * server holds about a mail, so it is checked here as well as there.
 */
fun mailLinkAddress(link: String): String? {
    if (link.isEmpty() || link.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null
    if (!link.startsWith("https://")) return null
    val rest = link.removePrefix("https://")
    val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
    // The text up to the first slash is the host and nothing else: no user part, no port.
    if (authority != MAIL_HOST) return null
    return link.takeIf { Uri.parse(it).host == MAIL_HOST }
}

/** Opens the message in Gmail; null for a link that does not pass [mailLinkAddress]. */
fun mailLinkIntent(link: String): Intent? = mailLinkAddress(link)?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }

fun siteIntent(website: String): Intent? = websiteAddress(website)?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }

/**
 * Starts the intent. Whether an app takes it is not asked beforehand (that needs a permission to
 * look at the other apps): it is started, and where none can, false is returned and nothing else happens.
 */
fun startOrSayNo(context: Context, intent: Intent): Boolean {
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (noApp: ActivityNotFoundException) {
        false
    } catch (refused: SecurityException) {
        false
    }
}
