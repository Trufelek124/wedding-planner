package io.github.trufelek124.weddingplanner.inspirations.internal;

import java.util.Optional;

/**
 * The kinds of picture the planner keeps. A file's kind is read from its first bytes: what the
 * sender calls it (a name, a declared type) is never believed.
 */
enum PictureFormat {
    JPEG("image/jpeg", true),
    PNG("image/png", true),
    GIF("image/gif", true),
    /** Kept and served as it is: Java cannot read it, so it gets no smaller version and no size. */
    WEBP("image/webp", false);

    private final String contentType;
    private final boolean readable;

    PictureFormat(String contentType, boolean readable) {
        this.contentType = contentType;
        this.readable = readable;
    }

    String contentType() {
        return contentType;
    }

    /** Whether the server can open pictures of this kind, to measure them and make them smaller. */
    boolean readable() {
        return readable;
    }

    static PictureFormat ofContentType(String contentType) {
        for (PictureFormat format : values()) {
            if (format.contentType.equals(contentType)) {
                return format;
            }
        }
        throw new IllegalArgumentException("Not a kind of picture that is kept: " + contentType);
    }

    /** The kind the bytes say they are; empty for anything that is not one of the kinds above. */
    static Optional<PictureFormat> of(byte[] start) {
        if (starts(start, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(JPEG);
        }
        if (starts(start, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(PNG);
        }
        if (starts(start, 'G', 'I', 'F', '8', '7', 'a') || starts(start, 'G', 'I', 'F', '8', '9', 'a')) {
            return Optional.of(GIF);
        }
        if (starts(start, 'R', 'I', 'F', 'F') && start.length >= 12 && at(start, 8, 'W', 'E', 'B', 'P')) {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    private static boolean starts(byte[] bytes, int... expected) {
        return at(bytes, 0, expected);
    }

    private static boolean at(byte[] bytes, int offset, int... expected) {
        if (bytes.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
