package io.github.trufelek124.weddingplanner.mailreading.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** The two steps' instructions: files in this module's resources, written in English and kept in the repository. */
record Instructions(String triage, String extraction) {

    static Instructions load() {
        return new Instructions(read("triage-instructions.txt"), read("extraction-instructions.txt"));
    }

    private static String read(String name) {
        try (InputStream in = Instructions.class.getResourceAsStream("/mailreading/" + name)) {
            if (in == null) {
                throw new IllegalStateException("The instructions are missing: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("The instructions could not be read: " + name, e);
        }
    }
}
