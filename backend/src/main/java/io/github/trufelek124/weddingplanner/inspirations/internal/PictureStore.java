package io.github.trufelek124.weddingplanner.inspirations.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * The pictures on disk, under the media directory. A file is named after the id the server gave the
 * picture and nothing else, so nothing a request says can ever point at another file.
 */
@Component
class PictureStore {

    private static final Logger log = LoggerFactory.getLogger(PictureStore.class);
    private static final String THUMBNAIL = ".thumb";

    private final Path directory;

    PictureStore(@Value("${app.media-dir}") String mediaDir) {
        if (mediaDir.isBlank()) {
            throw new IllegalStateException("APP_MEDIA_DIR is empty: say where the pictures are kept.");
        }
        Path media = Path.of(mediaDir).toAbsolutePath().normalize();
        this.directory = media.resolve("inspirations");
        try {
            Files.createDirectories(directory);
            // Where uploads wait while they arrive (spring.servlet.multipart.location).
            Files.createDirectories(media.resolve("incoming"));
        } catch (IOException e) {
            throw new IllegalStateException("The media directory cannot be used: " + media, e);
        }
        if (!Files.isWritable(directory)) {
            throw new IllegalStateException("The media directory cannot be written to: " + media);
        }
    }

    /** @param thumbnail the smaller version, or null when none could be made */
    void write(UUID id, byte[] original, byte[] thumbnail) throws IOException {
        put(file(id), original);
        if (thumbnail != null) {
            put(thumbnailFile(id), thumbnail);
        }
    }

    Resource original(UUID id) {
        return new FileSystemResource(file(id));
    }

    Resource thumbnail(UUID id) {
        return new FileSystemResource(thumbnailFile(id));
    }

    boolean holds(UUID id) {
        return Files.exists(file(id));
    }

    /** Removes what is there; a picture that is already gone is no error. */
    void delete(UUID id) {
        try {
            Files.deleteIfExists(file(id));
            Files.deleteIfExists(thumbnailFile(id));
        } catch (IOException e) {
            // The record is gone and the file is not: space is wasted, nothing else. Say so and go on.
            log.warn("Picture {} could not be removed from the disk", id, e);
        }
    }

    /** Written beside its place and then moved there, so that a half-written file is never served. */
    private static void put(Path target, byte[] bytes) throws IOException {
        Path arriving = target.resolveSibling(target.getFileName() + ".arriving");
        Files.write(arriving, bytes);
        Files.move(arriving, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path file(UUID id) {
        return directory.resolve(id.toString());
    }

    private Path thumbnailFile(UUID id) {
        return directory.resolve(id + THUMBNAIL);
    }
}
