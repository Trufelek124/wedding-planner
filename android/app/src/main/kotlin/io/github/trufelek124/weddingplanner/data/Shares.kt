package io.github.trufelek124.weddingplanner.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * What is shared to the planner from another app, on its way to the server: a link, or pictures,
 * with a board and a note. The server's limits are known here, so that what it would refuse is
 * said at once and not after a wait.
 */

/** @param link the link that was shared; null when pictures were */
data class Share(val link: String?, val boardId: String?, val note: String)

/** What keeping a share on the phone came to. */
sealed interface Kept {
    data class Yes(val inspirationId: String) : Kept

    /** More pictures than one inspiration holds. */
    data object TooMany : Kept

    /** One of the pictures is larger than the server takes. */
    data object TooLarge : Kept

    /** A picture could not be read from the app that shared it. */
    data object Unreadable : Kept
}

internal object ShareLimits {
    /** One inspiration holds at most this many pictures. */
    const val MOST_PICTURES = 10

    /** One picture is at most this large. */
    const val MOST_BYTES = 15L * 1024 * 1024
}

/** What is sent to save a share as an inspiration, as the API describes a new inspiration. */
internal fun requestOf(share: ShareRow): JsonObject = buildJsonObject {
    put("id", share.inspirationId)
    put("url", if (share.url == null) JsonNull else JsonPrimitive(share.url))
    put("boardId", if (share.boardId == null) JsonNull else JsonPrimitive(share.boardId))
    put("title", "")
    put("note", share.note)
    put("tags", JsonArray(emptyList()))
    put("planItemId", JsonNull)
    put("vendorId", JsonNull)
}

/**
 * The pictures of shares, in the app's own folder: one folder per share, the files numbered in the
 * order they were shared.
 */
internal class ShareFiles(private val root: File) {

    fun of(share: ShareRow): List<File> = (0 until share.pictures).map { File(File(root, share.inspirationId), it.toString()) }

    /**
     * Copies the pictures of a share in. All of them or none: when one is too large or cannot be
     * read, what was copied goes again.
     */
    fun keep(inspirationId: String, pictures: List<() -> InputStream?>): Kept {
        val folder = File(root, inspirationId)
        folder.mkdirs()
        try {
            pictures.forEachIndexed { number, open ->
                val from = open() ?: return failed(folder, Kept.Unreadable)
                from.use { stream ->
                    File(folder, number.toString()).outputStream().use { file ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            copied += read
                            if (copied > ShareLimits.MOST_BYTES) {
                                return failed(folder, Kept.TooLarge)
                            }
                            file.write(buffer, 0, read)
                        }
                    }
                }
            }
        } catch (unreadable: IOException) {
            return failed(folder, Kept.Unreadable)
        } catch (notLent: SecurityException) {
            // The other app took back its permission to read the picture.
            return failed(folder, Kept.Unreadable)
        }
        return Kept.Yes(inspirationId)
    }

    private fun failed(folder: File, why: Kept): Kept {
        folder.deleteRecursively()
        return why
    }

    fun delete(inspirationId: String) {
        File(root, inspirationId).deleteRecursively()
    }

    fun deleteAll() {
        root.deleteRecursively()
    }
}
