package io.github.trufelek124.weddingplanner.inspirations.internal;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/** Pictures made up for tests: white, with a red square in the top left corner and a blue one in the top right. */
public final class TestPictures {

    private static final int RED = 0xE00000;
    private static final int BLUE = 0x0000E0;

    private TestPictures() {}

    public static BufferedImage drawn(int width, int height) {
        BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D drawing = picture.createGraphics();
        int mark = Math.max(1, Math.min(width, height) / 4);
        drawing.setColor(Color.WHITE);
        drawing.fillRect(0, 0, width, height);
        drawing.setColor(new Color(RED));
        drawing.fillRect(0, 0, mark, mark);
        drawing.setColor(new Color(BLUE));
        drawing.fillRect(width - mark, 0, mark, mark);
        drawing.dispose();
        return picture;
    }

    public static byte[] jpeg(int width, int height) {
        return encoded(drawn(width, height), "jpeg");
    }

    public static byte[] png(int width, int height) {
        return encoded(drawn(width, height), "png");
    }

    public static byte[] gif(int width, int height) {
        return encoded(drawn(width, height), "gif");
    }

    /** The start of a WebP file. Java cannot read WebP, so the planner keeps such a file as it comes. */
    public static byte[] webp() {
        byte[] file = new byte[64];
        byte[] start = "RIFF8\u0000\u0000\u0000WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(start, 0, file, 0, start.length);
        return file;
    }

    /** A JPEG that carries the Exif note "show me with orientation n", as a phone's camera writes it. */
    public static byte[] jpegWithOrientation(int width, int height, int orientation) {
        byte[] plain = jpeg(width, height);
        byte[] exif = {
            (byte) 0xFF, (byte) 0xE1, 0x00, 0x22, // an APP1 block of 34 bytes
            'E', 'x', 'i', 'f', 0x00, 0x00,
            'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, // TIFF, big-endian, the directory starts at byte 8
            0x00, 0x01, // one entry
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, (byte) orientation, 0x00, 0x00, // orientation, one short
            0x00, 0x00, 0x00, 0x00, // no further directory
        };
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(plain, 0, 2); // start of image
        file.writeBytes(exif);
        file.write(plain, 2, plain.length - 2);
        return file.toByteArray();
    }

    public static BufferedImage read(byte[] picture) {
        try {
            return ImageIO.read(new ByteArrayInputStream(picture));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Which corner the red square is in and which the blue: "TL", "TR", "BL" or "BR" each. */
    public static String corners(BufferedImage picture) {
        return "red " + cornerOf(picture, true) + ", blue " + cornerOf(picture, false);
    }

    private static String cornerOf(BufferedImage picture, boolean red) {
        int inset = Math.max(1, Math.min(picture.getWidth(), picture.getHeight()) / 16);
        int[][] corners = {
            {inset, inset}, {picture.getWidth() - 1 - inset, inset},
            {inset, picture.getHeight() - 1 - inset}, {picture.getWidth() - 1 - inset, picture.getHeight() - 1 - inset},
        };
        String[] names = {"TL", "TR", "BL", "BR"};
        for (int i = 0; i < corners.length; i++) {
            Color colour = new Color(picture.getRGB(corners[i][0], corners[i][1]));
            boolean isRed = colour.getRed() > 150 && colour.getBlue() < 100 && colour.getGreen() < 100;
            boolean isBlue = colour.getBlue() > 150 && colour.getRed() < 100 && colour.getGreen() < 100;
            if (red ? isRed : isBlue) {
                return names[i];
            }
        }
        return "nowhere";
    }

    private static byte[] encoded(BufferedImage picture, String format) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(picture, format, bytes)) {
                throw new IllegalStateException("no writer for " + format);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
