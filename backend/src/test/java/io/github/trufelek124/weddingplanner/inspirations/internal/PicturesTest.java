package io.github.trufelek124.weddingplanner.inspirations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trufelek124.weddingplanner.inspirations.internal.Thumbnails.Measured;
import io.github.trufelek124.weddingplanner.inspirations.internal.Thumbnails.NotAPicture;
import io.github.trufelek124.weddingplanner.inspirations.internal.Thumbnails.TooManyPixels;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** What the planner makes of the bytes of a picture, before anything is stored. */
class PicturesTest {

    // ------------------------------------------------------------ what kind of file it is

    @Test
    void theKindIsReadFromTheFirstBytes() {
        assertThat(PictureFormat.of(TestPictures.jpeg(8, 8))).contains(PictureFormat.JPEG);
        assertThat(PictureFormat.of(TestPictures.png(8, 8))).contains(PictureFormat.PNG);
        assertThat(PictureFormat.of(TestPictures.gif(8, 8))).contains(PictureFormat.GIF);
        assertThat(PictureFormat.of(TestPictures.webp())).contains(PictureFormat.WEBP);
    }

    @Test
    void whatIsNotOneOfTheFourKindsIsNoPicture() {
        assertThat(PictureFormat.of("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(PictureFormat.of("<!doctype html><html><body>hello</body></html>".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(PictureFormat.of("%PDF-1.7".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(PictureFormat.of("RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1))).as("a sound file is a RIFF file too").isEmpty();
        assertThat(PictureFormat.of(new byte[0])).isEmpty();
        assertThat(PictureFormat.of(new byte[] {(byte) 0xFF, (byte) 0xD8})).as("too short to say").isEmpty();
    }

    // ------------------------------------------------------------ measuring and making smaller

    @Test
    void aLargePictureGetsASmallerVersionOfTheSameShape() throws Exception {
        Measured measured = Thumbnails.measure(TestPictures.jpeg(2400, 1200), PictureFormat.JPEG);

        assertThat(measured.width()).isEqualTo(2400);
        assertThat(measured.height()).isEqualTo(1200);
        BufferedImage thumbnail = TestPictures.read(measured.thumbnail());
        assertThat(thumbnail.getWidth()).isEqualTo(640);
        assertThat(thumbnail.getHeight()).isEqualTo(320);
        assertThat(PictureFormat.of(measured.thumbnail())).contains(PictureFormat.JPEG);
        assertThat(TestPictures.corners(thumbnail)).isEqualTo("red TL, blue TR");
    }

    @Test
    void aSmallPictureIsNotMadeLarger() throws Exception {
        Measured measured = Thumbnails.measure(TestPictures.png(120, 80), PictureFormat.PNG);

        BufferedImage thumbnail = TestPictures.read(measured.thumbnail());
        assertThat(thumbnail.getWidth()).isEqualTo(120);
        assertThat(thumbnail.getHeight()).isEqualTo(80);
    }

    @Test
    void everyReadableKindIsMeasured() throws Exception {
        assertThat(Thumbnails.measure(TestPictures.gif(300, 200), PictureFormat.GIF).width()).isEqualTo(300);
        assertThat(Thumbnails.measure(TestPictures.png(300, 200), PictureFormat.PNG).height()).isEqualTo(200);
    }

    @Test
    void aFileThatOnlyStartsLikeAPictureIsNotOne() {
        byte[] cut = Arrays.copyOf(TestPictures.jpeg(400, 300), 40);
        byte[] disguised = "ÿØÿ<html><script>alert(1)</script></html>".getBytes(StandardCharsets.ISO_8859_1);

        assertThatThrownBy(() -> Thumbnails.measure(cut, PictureFormat.JPEG)).isInstanceOf(NotAPicture.class);
        assertThatThrownBy(() -> Thumbnails.measure(disguised, PictureFormat.JPEG)).isInstanceOf(NotAPicture.class);
    }

    @Test
    void aPictureThatClaimsToBeEnormousIsNotOpened() {
        // A PNG's size is in bytes 16 to 23. This one says 30,000 by 30,000: 900 million pixels in a file of a few bytes.
        byte[] claiming = TestPictures.png(4, 4);
        byte[] thirtyThousand = {0x00, 0x00, 0x75, 0x30};
        System.arraycopy(thirtyThousand, 0, claiming, 16, 4);
        System.arraycopy(thirtyThousand, 0, claiming, 20, 4);

        assertThatThrownBy(() -> Thumbnails.measure(claiming, PictureFormat.PNG)).isInstanceOf(TooManyPixels.class);
    }

    // ------------------------------------------------------------ which way up

    @ParameterizedTest
    @CsvSource({
        "1, 'red TL, blue TR', 40, 20",
        "2, 'red TR, blue TL', 40, 20",
        "3, 'red BR, blue BL', 40, 20",
        "4, 'red BL, blue BR', 40, 20",
        "5, 'red TL, blue BL', 20, 40",
        "6, 'red TR, blue BR', 20, 40",
        "7, 'red BR, blue TR', 20, 40",
        "8, 'red BL, blue TL', 20, 40",
    })
    void aStoredPictureIsTurnedTheWayItsNoteAsks(int orientation, String corners, int width, int height) {
        BufferedImage shown = Thumbnails.upright(TestPictures.drawn(40, 20), orientation);

        assertThat(TestPictures.corners(shown)).isEqualTo(corners);
        assertThat(shown.getWidth()).isEqualTo(width);
        assertThat(shown.getHeight()).isEqualTo(height);
    }

    @Test
    void aPhonePhotoIsMeasuredAndMadeSmallerTheRightWayUp() throws Exception {
        // Stored lying down, 800 by 400, with the note "a quarter turn clockwise": it is a standing picture.
        byte[] photo = TestPictures.jpegWithOrientation(800, 400, 6);

        assertThat(ExifOrientation.of(photo)).isEqualTo(6);
        Measured measured = Thumbnails.measure(photo, PictureFormat.JPEG);
        assertThat(measured.width()).isEqualTo(400);
        assertThat(measured.height()).isEqualTo(800);
        BufferedImage thumbnail = TestPictures.read(measured.thumbnail());
        assertThat(thumbnail.getWidth()).isEqualTo(320);
        assertThat(thumbnail.getHeight()).isEqualTo(640);
        assertThat(TestPictures.corners(thumbnail)).isEqualTo("red TR, blue BR");
    }

    @Test
    void aPictureWithoutANoteOrWithABrokenOneIsShownAsStored() {
        assertThat(ExifOrientation.of(TestPictures.jpeg(40, 20))).isEqualTo(1);
        assertThat(ExifOrientation.of(TestPictures.jpegWithOrientation(40, 20, 9))).as("no such orientation").isEqualTo(1);
        byte[] cut = Arrays.copyOf(TestPictures.jpegWithOrientation(40, 20, 6), 20);
        assertThat(ExifOrientation.of(cut)).as("the note is cut off").isEqualTo(1);
        assertThat(ExifOrientation.of(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x7F, 0x7F})).isEqualTo(1);
    }
}
