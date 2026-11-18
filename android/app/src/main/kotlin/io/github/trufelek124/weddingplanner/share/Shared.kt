package io.github.trufelek124.weddingplanner.share

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * What another app handed over through Android's share sheet.
 *
 * @param link the first web address in what was shared; null when there is none
 * @param text what was shared as text when it holds no link: it becomes the note
 * @param pictures the pictures that were shared; the other app lends them for reading
 */
data class Shared(val link: String?, val text: String, val pictures: List<Uri>) {

    /** Nothing the planner could keep. */
    val empty: Boolean get() = link == null && text.isBlank() && pictures.isEmpty()
}

private val ADDRESS = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)

/**
 * The first web address in a text. Instagram shares the bare address; TikTok and others put words
 * around it. What ends a sentence after the address is not part of it.
 */
fun linkIn(text: String): String? = ADDRESS.find(text)?.value?.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '\'', '"')

/** Reads a share out of the request Android starts the app with. */
fun sharedOf(intent: Intent?): Shared {
    val nothing = Shared(null, "", emptyList())
    if (intent == null) {
        return nothing
    }
    val type = intent.type.orEmpty()
    return when {
        intent.action == Intent.ACTION_SEND && type.startsWith("image/") ->
            Shared(null, "", listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)))
        intent.action == Intent.ACTION_SEND_MULTIPLE && type.startsWith("image/") ->
            Shared(null, "", IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty())
        intent.action == Intent.ACTION_SEND -> {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            val link = linkIn(text)
            // With a link, the words around it are the other app's advertising and are left out.
            Shared(link, if (link == null) text.trim() else "", emptyList())
        }
        else -> nothing
    }
}
