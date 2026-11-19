package io.github.trufelek124.weddingplanner.planner

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.trufelek124.weddingplanner.data.Inspiration
import io.github.trufelek124.weddingplanner.data.Picture
import io.github.trufelek124.weddingplanner.data.PictureSize
import io.github.trufelek124.weddingplanner.data.WaitingShare

/** What the picture on a card is tagged with, for the tests that look for it: it has no description to find it by. */
const val FACE_PICTURE = "face-picture"

/** How high the picture on a card of the board is; the picture fills it, cropped to its shape. */
private val FACE_HEIGHT = 168.dp

/** The shape of a picture whose size the server did not say. */
private const val UNKNOWN_SHAPE = 4f / 3f

/**
 * The picture on a card of the board: the first picture's small version in its own shape, within bounds ([boardShape]),
 * cropped from the centre where it is outside them. The place is as high as it will be from the start, because the server
 * says the picture's size before its bytes arrive. It is decoration next to the card's statement, which says what the card
 * holds: it has no description of its own. While it is on its way the place says nothing; where the phone neither holds it
 * nor can fetch it, the face says in words what is there, at the height a words face has.
 */
@Composable
fun PictureFace(idea: Inspiration, pictures: PicturesModel) {
    val first = idea.pictures.firstOrNull()
    if (first == null) {
        WordsFace(faceInWords(idea))
        return
    }
    val shape = boardShape(first.width, first.height)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        val height = (width / shape).toInt().coerceAtLeast(1)
        val arrived by pictures.arrived.collectAsState()
        val quick = remember(idea.id, first.id, width, height) { pictures.quick(idea.id, first.id, PictureSize.THUMBNAIL, width, height) }
        val shown by produceState(quick, idea.id, first.id, width, height, arrived) {
            value = pictures.picture(idea.id, first.id, PictureSize.THUMBNAIL, width, height)
        }
        Face(shown, words = faceInWords(idea), shape = shape)
    }
}

/**
 * The picture of a share that waits to be sent, from the phone's own copy. Its size is not known before its file is read:
 * the face is the height of a words face while it is read, and takes the picture's own bounded shape once it is.
 */
@Composable
fun SharePictureFace(share: WaitingShare, pictures: PicturesModel) {
    if (share.pictures == 0) {
        WordsFace(shareFaceInWords(share))
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        // The least height the picture can need: the flattest shape there is.
        val height = (width / FLATTEST).toInt().coerceAtLeast(1)
        val first = remember(share.id, width, height) { pictures.quickOfShare(share, 0, width, height) }
        val shown by produceState(first, share.id, width, height) { value = pictures.ofShare(share, 0, width, height) }
        val ready = shown as? Shown.Ready
        Face(shown, words = shareFaceInWords(share), shape = ready?.let { boardShape(it.bitmap.width, it.bitmap.height) })
    }
}

/** @param shape width over height of the place; null while it is not known, which is a place of the height of a words face */
@Composable
private fun Face(shown: Shown, words: List<String>, shape: Float?) {
    val place = Modifier.fillMaxWidth().let { if (shape == null) it.height(FACE_HEIGHT) else it.aspectRatio(shape) }
    when (shown) {
        Shown.Loading -> Box(place.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest))
        is Shown.Ready -> Image(
            bitmap = shown.bitmap.asImageBitmap(),
            // Decoration: the card says what it holds.
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = place.clip(MaterialTheme.shapes.small).testTag(FACE_PICTURE),
        )
        Shown.Missing -> WordsFace(words, modifier = Modifier.fillMaxWidth().heightIn(min = FACE_HEIGHT))
    }
}

/**
 * Every picture of an idea on its own screen, the whole version, one under the other in the idea's proportions: they are
 * as high as they will be from the start, so that the page does not jump when they arrive. Each is its own item of the
 * list, so that only those near the window are fetched and decoded.
 */
fun LazyListScope.pictureItems(idea: Inspiration, pictures: PicturesModel) {
    idea.pictures.forEachIndexed { index, picture ->
        item(key = "picture-${picture.id}") {
            WholePicture(idea.id, picture, index + 1, idea.pictures.size, pictures)
        }
    }
}

@Composable
private fun WholePicture(inspirationId: String, picture: Picture, number: Int, of: Int, pictures: PicturesModel) {
    val shape = shapeOf(picture)
    val said = pictureInWords(number, of)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        // The place is as wide as the page and as high as the picture's own shape makes it.
        val width = constraints.maxWidth
        val height = (width / shape).toInt().coerceAtLeast(1)
        val arrived by pictures.arrived.collectAsState()
        val first = remember(inspirationId, picture.id, width, height) { pictures.quick(inspirationId, picture.id, PictureSize.FULL, width, height) }
        val shown: State<Shown> = produceState(first, inspirationId, picture.id, width, height, arrived) {
            // The small version first, when the phone has it, until the whole one is there.
            if (value !is Shown.Ready) {
                val small = pictures.held(inspirationId, picture.id, PictureSize.THUMBNAIL, width, height)
                if (small is Shown.Ready) value = small
            }
            val whole = pictures.picture(inspirationId, picture.id, PictureSize.FULL, width, height)
            if (whole is Shown.Ready || value !is Shown.Ready) value = whole
        }
        val now = shown.value
        val place = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest)
        when (now) {
            Shown.Loading -> Box(place.aspectRatio(shape).semantics { contentDescription = said })
            is Shown.Ready -> Image(
                bitmap = now.bitmap.asImageBitmap(),
                contentDescription = said,
                contentScale = ContentScale.Fit,
                modifier = place.aspectRatio(shape),
            )
            // Nothing to be shown in its shape: a line of words is all it needs.
            Shown.Missing -> Box(
                place.heightIn(min = 72.dp).semantics(mergeDescendants = true) { contentDescription = "$said. $PICTURE_MISSING" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    PICTURE_MISSING, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

/** The flattest a picture on the board is shown: 16:9. */
private const val FLATTEST = 16f / 9f

/** The tallest a picture on the board is shown: 5:4, height over width. */
private const val TALLEST = 4f / 5f

/**
 * The shape (width over height) a picture has on the board: its own, bounded to no taller than 5:4 and no flatter than
 * 16:9, so that a tall photograph does not take the board over and a panorama does not shrink to a line. Outside the
 * bounds it is cropped from the centre to the nearest one. 4:3 where the server did not say, as on the idea's screen.
 */
fun boardShape(width: Int?, height: Int?): Float {
    if (width == null || height == null || width <= 0 || height <= 0) return UNKNOWN_SHAPE
    return (width.toFloat() / height).coerceIn(TALLEST, FLATTEST)
}

/** The shape of a picture as the server knows it: width over height; a guess when it did not say. */
internal fun shapeOf(picture: Picture): Float {
    val width = picture.width
    val height = picture.height
    return if (width != null && height != null && width > 0 && height > 0) width.toFloat() / height else UNKNOWN_SHAPE
}
