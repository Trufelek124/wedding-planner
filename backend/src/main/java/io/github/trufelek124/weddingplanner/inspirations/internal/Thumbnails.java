package io.github.trufelek124.weddingplanner.inspirations.internal;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/** Measures a picture and makes the smaller version the board shows. */
final class Thumbnails {

    /** The longer side of the smaller version, in pixels. */
    static final int LONGER_SIDE = 640;

    /**
     * No picture with more pixels than this is taken. A file of a few bytes can claim to be enormous;
     * opening such a picture, here or in a browser, would take memory the file's size gives no hint of.
     */
    static final long MOST_PIXELS = 100_000_000L;

    private static final float JPEG_QUALITY = 0.82f;

    private Thumbnails() {}

    /**
     * What was found out about a picture.
     *
     * @param width as a person sees it, the right way up
     * @param thumbnail a JPEG no larger than {@link #LONGER_SIDE} on its longer side
     */
    record Measured(int width, int height, byte[] thumbnail) {}

    static final class NotAPicture extends Exception {
        NotAPicture(String why) {
            super(why);
        }
    }

    static final class TooManyPixels extends Exception {}

    /**
     * Opens a picture of a kind the server can read.
     *
     * @throws NotAPicture when the bytes only start like a picture
     * @throws TooManyPixels when it is larger than {@link #MOST_PIXELS}
     */
    static Measured measure(byte[] bytes, PictureFormat format) throws NotAPicture, TooManyPixels {
        int orientation = format == PictureFormat.JPEG ? ExifOrientation.of(bytes) : ExifOrientation.AS_STORED;
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new NotAPicture("no reader");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new NotAPicture("no size");
                }
                if ((long) width * height > MOST_PIXELS) {
                    throw new TooManyPixels();
                }
                // Read every n-th pixel only: the picture is never held in memory at its full size.
                ImageReadParam smaller = reader.getDefaultReadParam();
                int step = Math.max(1, Math.max(width, height) / (LONGER_SIDE * 2));
                smaller.setSourceSubsampling(step, step, 0, 0);
                BufferedImage read = reader.read(0, smaller);
                byte[] thumbnail = jpeg(upright(scaled(read), orientation));
                boolean onItsSide = ExifOrientation.turnsOnItsSide(orientation);
                return new Measured(onItsSide ? height : width, onItsSide ? width : height, thumbnail);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException unreadable) {
            throw new NotAPicture(Optional.ofNullable(unreadable.getMessage()).orElse(unreadable.getClass().getSimpleName()));
        }
    }

    /** At most {@link #LONGER_SIDE} on the longer side, on white where the picture is see-through. */
    private static BufferedImage scaled(BufferedImage picture) {
        double factor = Math.min(1.0, (double) LONGER_SIDE / Math.max(picture.getWidth(), picture.getHeight()));
        int width = Math.max(1, (int) Math.round(picture.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(picture.getHeight() * factor));
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D drawing = result.createGraphics();
        try {
            drawing.setColor(Color.WHITE);
            drawing.fillRect(0, 0, width, height);
            drawing.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            drawing.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            drawing.drawImage(picture, 0, 0, width, height, null);
        } finally {
            drawing.dispose();
        }
        return result;
    }

    /**
     * Turns and mirrors the picture the way its Exif note asks. Each case says where a point of the stored
     * picture (x, y) ends up; w and h are the stored picture's width and height.
     */
    static BufferedImage upright(BufferedImage picture, int orientation) {
        int w = picture.getWidth();
        int h = picture.getHeight();
        // AffineTransform(m00, m10, m01, m11, m02, m12): x' = m00*x + m01*y + m02, y' = m10*x + m11*y + m12
        AffineTransform turn = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0); // (w - x, y): mirrored left to right
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h); // (w - x, h - y): half a turn
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h); // (x, h - y): mirrored top to bottom
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0); // (y, x): flipped over the falling diagonal
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0); // (h - y, x): a quarter turn clockwise
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w); // (h - y, w - x): flipped over the rising diagonal
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w); // (y, w - x): a quarter turn the other way
            default -> null;
        };
        if (turn == null) {
            return picture;
        }
        boolean onItsSide = ExifOrientation.turnsOnItsSide(orientation);
        BufferedImage result = new BufferedImage(onItsSide ? h : w, onItsSide ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D drawing = result.createGraphics();
        try {
            drawing.drawImage(picture, turn, null);
        } finally {
            drawing.dispose();
        }
        return result;
    }

    private static byte[] jpeg(BufferedImage picture) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam quality = writer.getDefaultWriteParam();
            quality.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            quality.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(picture, null, null), quality);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
