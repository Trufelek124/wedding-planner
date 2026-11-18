package io.github.trufelek124.weddingplanner.inspirations.internal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Which way up a JPEG is meant to be shown. Phones store a photo as the sensor saw it and note the
 * turn to apply in the file's Exif data. Browsers follow the note; Java's reader does not, so a
 * smaller version made without it would lie on its side.
 *
 * <p>The values are those of the Exif standard: 1 as stored, 3 turned half round, 6 a quarter turn
 * clockwise, 8 a quarter turn the other way, and 2, 4, 5, 7 the mirrored forms of those.
 */
final class ExifOrientation {

    static final int AS_STORED = 1;
    private static final int ORIENTATION_TAG = 0x0112;

    private ExifOrientation() {}

    /** The orientation noted in a JPEG's bytes, or {@link #AS_STORED} when there is no usable note. */
    static int of(byte[] jpeg) {
        try {
            return read(jpeg);
        } catch (RuntimeException brokenNote) {
            // A damaged note is no reason to refuse the picture; it is shown as stored.
            return AS_STORED;
        }
    }

    /** Whether a picture with this orientation is shown with its width and height swapped. */
    static boolean turnsOnItsSide(int orientation) {
        return orientation >= 5 && orientation <= 8;
    }

    private static int read(byte[] jpeg) {
        ByteBuffer file = ByteBuffer.wrap(jpeg).order(ByteOrder.BIG_ENDIAN);
        int at = 2; // after the start-of-image marker
        while (at + 4 <= jpeg.length) {
            if ((file.get(at) & 0xFF) != 0xFF) {
                return AS_STORED;
            }
            int marker = file.get(at + 1) & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                return AS_STORED; // the picture itself starts: no Exif before it
            }
            int length = file.getShort(at + 2) & 0xFFFF;
            if (length < 2) {
                return AS_STORED;
            }
            boolean exif = marker == 0xE1 && length >= 8 && at + 10 <= jpeg.length
                    && file.get(at + 4) == 'E' && file.get(at + 5) == 'x' && file.get(at + 6) == 'i' && file.get(at + 7) == 'f';
            if (exif) {
                return fromTiff(jpeg, at + 10, at + 2 + length);
            }
            at += 2 + length;
        }
        return AS_STORED;
    }

    /** The Exif block is a small TIFF file: a byte order, then a directory of numbered entries. */
    private static int fromTiff(byte[] jpeg, int start, int end) {
        int limit = Math.min(end, jpeg.length);
        if (start + 8 > limit) {
            return AS_STORED;
        }
        ByteBuffer tiff = ByteBuffer.wrap(jpeg, start, limit - start).slice();
        char order = (char) tiff.get(0);
        if (order != 'I' && order != 'M') {
            return AS_STORED;
        }
        tiff.order(order == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        int directory = tiff.getInt(4);
        if (directory < 8 || directory + 2 > tiff.limit()) {
            return AS_STORED;
        }
        int entries = tiff.getShort(directory) & 0xFFFF;
        for (int i = 0; i < entries; i++) {
            int entry = directory + 2 + i * 12;
            if (entry + 12 > tiff.limit()) {
                return AS_STORED;
            }
            if ((tiff.getShort(entry) & 0xFFFF) == ORIENTATION_TAG) {
                int value = tiff.getShort(entry + 8) & 0xFFFF;
                return value >= 1 && value <= 8 ? value : AS_STORED;
            }
        }
        return AS_STORED;
    }
}
