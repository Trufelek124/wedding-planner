package io.github.trufelek124.weddingplanner.planner

import android.net.Uri
import io.github.trufelek124.weddingplanner.data.Board
import io.github.trufelek124.weddingplanner.data.Inspiration
import io.github.trufelek124.weddingplanner.data.InspirationSource
import io.github.trufelek124.weddingplanner.data.Waiting
import io.github.trufelek124.weddingplanner.data.WaitingShare
import java.util.Locale

/*
 * How the inspirations read on the phone: the same words as the web's board
 * (web/src/inspirations/labels.ts, InspirationCard.tsx, InspirationsPage.tsx). Each function says what
 * one place on the screen says, so that the words are tested without a screen. A title, a link, a note
 * and a tag are the couple's own: they appear in what a person reads and nowhere else.
 */

/** What the choice "no board" is called when it is kept: no board's id looks like this. */
const val ON_NO_BOARD = "none"

/** What stands in the place of a picture while a preview is being fetched. */
const val FETCHING_A_PREVIEW = "Fetching a preview\u2026"

/** What stands in the place of a picture of an idea that has neither a link nor a picture. */
const val NO_PICTURE = "No picture"

/** The site a link is on, without "www.": null where the link is no address a browser could open (an http or https one). */
private fun hostOf(url: String): String? =
    websiteAddress(url)?.let { Uri.parse(it).host }?.removePrefix("www.")?.takeIf { it.isNotBlank() }

private fun isOrIsUnder(host: String, site: String) = host == site || host.endsWith(".$site")

/** Pinterest has an address per country (pinterest.com, pinterest.co.uk, pl.pinterest.com, pinterest.de), and pin.it. */
private fun isPinterest(host: String): Boolean {
    if (isOrIsUnder(host, "pin.it")) return true
    val labels = host.split('.')
    for (at in 0 until labels.size - 1) {
        if (labels[at] == "pinterest") {
            val after = labels.size - 1 - at
            if (after == 1 || (after == 2 && labels[at + 1].length <= 3 && labels[at + 2].length == 2)) return true
        }
    }
    return false
}

/**
 * Which of the known places a link leads to, as the server works it out: for what is shared on the phone and
 * not sent yet, which the server has not looked at. Any other address is [InspirationSource.WEB].
 */
fun sourceOfLink(url: String): InspirationSource {
    val host = websiteAddress(url)?.let { Uri.parse(it).host }?.lowercase(Locale.ROOT) ?: return InspirationSource.WEB
    return when {
        isOrIsUnder(host, "instagram.com") || isOrIsUnder(host, "instagr.am") -> InspirationSource.INSTAGRAM
        isOrIsUnder(host, "tiktok.com") -> InspirationSource.TIKTOK
        isPinterest(host) -> InspirationSource.PINTEREST
        else -> InspirationSource.WEB
    }
}

/**
 * "Instagram", "TikTok", "Pinterest" or "Photo"; for a link elsewhere on the web the site's own name
 * ("palace.example"), and "Web" where the link cannot be read. "Other" is a source this app does not know.
 */
fun sourceName(source: InspirationSource, url: String?): String = when (source) {
    InspirationSource.INSTAGRAM -> "Instagram"
    InspirationSource.TIKTOK -> "TikTok"
    InspirationSource.PINTEREST -> "Pinterest"
    InspirationSource.PHOTO -> "Photo"
    InspirationSource.OTHER -> "Other"
    InspirationSource.WEB -> url?.let(::hostOf) ?: "Web"
}

fun sourceName(idea: Inspiration): String = sourceName(idea.source, idea.url)

/** What an idea is called: the title a person gave it, else the page's own, else where it comes from, else what it is. */
fun inspirationName(idea: Inspiration): String = when {
    idea.title.isNotEmpty() -> idea.title
    idea.previewTitle.isNotEmpty() -> idea.previewTitle
    idea.url != null -> "From ${sourceName(idea)}"
    else -> whatItIs(idea.pictures.size)
}

private fun whatItIs(pictures: Int): String = when (pictures) {
    0 -> "An idea"
    1 -> "A picture"
    else -> "Pictures"
}

/** "1 picture", "3 pictures" */
fun picturesInWords(pictures: Int): String = if (pictures == 1) "1 picture" else "$pictures pictures"

/** What stands where a picture is when the phone neither holds it nor can fetch it: the web's words. */
const val PICTURE_MISSING = "This picture could not be loaded."

/** What a picture on an idea's own screen says to a screen reader: "Picture 2 of 3". [number] counts from 1. */
fun pictureInWords(number: Int, of: Int): String = "Picture $number of $of"

/** "1 more picture", "3 more pictures": the pictures after the one the card shows; null where there are none. */
fun morePicturesInWords(more: Int): String? = when {
    more <= 0 -> null
    more == 1 -> "1 more picture"
    else -> "$more more pictures"
}

/** "For Book the venue" */
fun forPlanItemInWords(planItem: String): String = "For $planItem"

/**
 * What stands where the picture belongs, until the pictures themselves are shown: where a link comes from,
 * "No picture" for an idea with neither link nor picture, how many pictures it holds, and that a preview is
 * being fetched.
 */
fun faceInWords(idea: Inspiration): List<String> {
    val what = when {
        idea.pictures.isNotEmpty() -> picturesInWords(idea.pictures.size)
        idea.url != null -> sourceName(idea)
        else -> NO_PICTURE
    }
    return listOfNotNull(what, FETCHING_A_PREVIEW.takeIf { idea.previewPending })
}

/** The facts in one line under an idea's name: where a link comes from, its board, its plan item, the pictures after the first. */
fun inspirationFacts(idea: Inspiration, board: String?, planItem: String?): List<String> = listOfNotNull(
    sourceName(idea).takeIf { idea.url != null },
    board,
    planItem?.let(::forPlanItemInWords),
    morePicturesInWords(idea.pictures.size - 1),
)

/**
 * A card as one statement for a screen reader: its name, what stands in the place of the picture, the facts,
 * the note and the tags. What is said twice (a link's source is the face and a fact) is said once.
 */
fun inspirationInWords(idea: Inspiration, board: String?, planItem: String?): String =
    (listOf(inspirationName(idea)) + (faceInWords(idea) + inspirationFacts(idea, board, planItem)).distinct() +
        listOfNotNull(idea.note.takeIf { it.isNotEmpty() }, tagsInWords(idea.tags)))
        .joinToString(". ")

private fun tagsInWords(tags: List<String>): String? = tags.takeIf { it.isNotEmpty() }?.let { "Tags: " + it.joinToString(", ") }

/** What a tag button on a card says to a screen reader. */
fun showOnlyTagInWords(tag: String): String = "Show only \u201c$tag\u201d"

/** "Open the original on Instagram", "Open the original on palace.example" */
fun openOriginalInWords(idea: Inspiration): String = "Open the original on ${sourceName(idea)}"

fun addedByInWords(name: String): String = "Added by $name"

// ---------------------------------------------------------------- what was shared on this phone and is not sent yet

/** What a waiting share is called, as an idea without a title would be. */
fun shareName(share: WaitingShare): String = when {
    share.link != null -> "From ${sourceName(sourceOfLink(share.link), share.link)}"
    else -> whatItIs(share.pictures)
}

/** What stands in the place of a waiting share's picture. */
fun shareFaceInWords(share: WaitingShare): List<String> = listOf(
    when {
        share.link != null -> sourceName(sourceOfLink(share.link), share.link)
        share.pictures > 0 -> picturesInWords(share.pictures)
        else -> NO_PICTURE
    },
)

/** The facts under a waiting share's name: where its link comes from. */
fun shareFacts(share: WaitingShare): List<String> = listOfNotNull(share.link?.let { sourceName(sourceOfLink(it), it) })

/** A waiting share as one statement for a screen reader. */
fun shareInWords(share: WaitingShare): String =
    (listOf(shareName(share), waitingInWords(Waiting.TO_BE_SENT).orEmpty()) + (shareFaceInWords(share) + shareFacts(share)).distinct())
        .joinToString(". ")

// ---------------------------------------------------------------- More

/**
 * The one line under "Inspirations" in the list of what More leads to: "12 inspirations", and where shares wait to
 * be sent "12 inspirations, 2 waiting to be sent"; "Nothing kept yet" where there is nothing at all.
 */
fun inspirationsSummary(ideas: List<Inspiration>, waiting: Int): String {
    val kept = inspirationsInWords(ideas.size) ?: "Nothing kept yet"
    return if (waiting > 0) "$kept, $waiting waiting to be sent" else kept
}

// ---------------------------------------------------------------- narrowing the board down

/**
 * The boards to offer: each that exists, in the server's order, and "no board" where an idea or a waiting share is
 * on none, or that was chosen. Each as the key that is kept when it is chosen: the board's id, or [ON_NO_BOARD].
 */
fun boardsOffered(boards: List<Board>, boardIdsOfTheThings: List<String?>, chosen: String?): List<String> =
    boards.map { it.id } + listOfNotNull(ON_NO_BOARD.takeIf { chosen == ON_NO_BOARD || boardIdsOfTheThings.any { it == null } })

/** What a board's chip says. */
fun boardChipLabel(key: String, boards: List<Board>): String =
    if (key == ON_NO_BOARD) "On no board" else boards.firstOrNull { it.id == key }?.name.orEmpty()

/**
 * The board that was kept as chosen, as long as it is one the phone knows or "no board"; null (all boards) for
 * nothing chosen and for a board that is gone since.
 */
fun boardChosen(kept: String, boards: List<Board>): String? = kept.takeIf { it == ON_NO_BOARD || boards.any { board -> board.id == it } }

/**
 * The tags to offer: those that occur, the most used first and then by name. The one that is chosen stays offered
 * when its last idea is gone (after a load), so that the list can say that nothing matches instead of changing what
 * was asked for.
 */
fun tagsOffered(ideas: List<Inspiration>, chosen: String? = null): List<String> {
    val used = ideas.flatMap { it.tags.distinct() }.groupingBy { it }.eachCount()
    val all = (used.keys + listOfNotNull(chosen)).distinct()
    return all.sortedWith(compareBy<String>({ -(used[it] ?: 0) }, { it.lowercase(Locale.ROOT) }, { it }))
}

private fun onBoard(boardId: String?, board: String?): Boolean = board == null || if (board == ON_NO_BOARD) boardId == null else boardId == board

/** What the board shows: one board or all, one tag or any. [board] is a key from [boardsOffered]. */
fun ideasShown(ideas: List<Inspiration>, board: String?, tag: String?): List<Inspiration> =
    ideas.filter { onBoard(it.boardId, board) && (tag == null || tag in it.tags) }

/** What waits to be sent and belongs to what is shown: a waiting share has no tags, so a tag leaves it out. */
fun sharesShown(shares: List<WaitingShare>, board: String?, tag: String?): List<WaitingShare> =
    if (tag != null) emptyList() else shares.filter { onBoard(it.boardId, board) }
