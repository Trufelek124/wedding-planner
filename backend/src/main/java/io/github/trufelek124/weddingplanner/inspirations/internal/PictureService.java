package io.github.trufelek124.weddingplanner.inspirations.internal;

import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.ProblemCode;
import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.inspirations.internal.PictureRepository.PictureRow;
import io.github.trufelek124.weddingplanner.inspirations.internal.Thumbnails.Measured;
import io.github.trufelek124.weddingplanner.inspirations.internal.Thumbnails.NotAPicture;
import io.github.trufelek124.weddingplanner.inspirations.internal.Thumbnails.TooManyPixels;
import io.github.trufelek124.weddingplanner.wedding.Membership;
import io.github.trufelek124.weddingplanner.wedding.Weddings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/** The pictures of inspirations: what is taken, where it goes, and who gets it back. */
@Service
class PictureService {

    /** How many pictures one inspiration holds. */
    static final int MOST_PICTURES = 10;

    private static final String FIELD = "files";
    private static final int NAME_SHOWN = 60;

    private final PictureRepository pictures;
    private final PictureStore store;
    private final InspirationRepository inspirations;
    private final Weddings weddings;
    private final Clock clock;

    PictureService(
            PictureRepository pictures, PictureStore store, InspirationRepository inspirations, Weddings weddings, Clock clock) {
        this.pictures = pictures;
        this.store = store;
        this.inspirations = inspirations;
        this.weddings = weddings;
        this.clock = clock;
    }

    /** Which version of a picture is asked for. */
    enum Size {
        /** The smaller version for the board; the picture itself where none could be made. */
        THUMBNAIL,
        FULL
    }

    /** A picture ready to be sent. */
    record Served(Resource bytes, String contentType) {}

    /**
     * Adds pictures to an inspiration. All of them are checked before any is kept: if one is
     * refused, none is added.
     */
    @Transactional
    void add(UserId user, UUID inspirationId, List<MultipartFile> files) {
        Membership membership = weddings.membershipOf(user);
        if (inspirations.find(membership.weddingId(), inspirationId).isEmpty()) {
            throw noSuchInspiration();
        }
        if (files == null || files.isEmpty()) {
            throw ApiException.invalidField(FIELD, "Choose at least one picture.");
        }
        if (pictures.count(inspirationId) + files.size() > MOST_PICTURES) {
            throw ApiException.invalidField(FIELD, "An inspiration holds at most " + MOST_PICTURES + " pictures.");
        }

        record Checked(UUID id, byte[] bytes, PictureFormat format, Measured measured) {}
        List<Checked> checked = new ArrayList<>();
        for (MultipartFile file : files) {
            byte[] bytes = bytesOf(file);
            String name = nameShown(file);
            PictureFormat format = PictureFormat.of(Arrays.copyOf(bytes, Math.min(bytes.length, 16)))
                    .orElseThrow(() -> ApiException.invalidField(
                            FIELD, name + " is not a picture the planner keeps. Use a JPEG, PNG, GIF or WebP picture."));
            Measured measured = null;
            if (format.readable()) {
                try {
                    measured = Thumbnails.measure(bytes, format);
                } catch (NotAPicture unreadable) {
                    throw ApiException.invalidField(FIELD, name + " could not be read as a picture.");
                } catch (TooManyPixels tooLarge) {
                    throw ApiException.invalidField(FIELD, name + " has more pixels than the planner takes.");
                }
            }
            checked.add(new Checked(UUID.randomUUID(), bytes, format, measured));
        }

        // If the records are not kept after all, the files must not stay behind.
        forget(checked.stream().map(Checked::id).toList(), TransactionSynchronization.STATUS_ROLLED_BACK);
        Instant now = clock.instant();
        for (Checked one : checked) {
            Measured measured = one.measured();
            try {
                store.write(one.id(), one.bytes(), measured == null ? null : measured.thumbnail());
            } catch (IOException e) {
                throw new UncheckedIOException("A picture could not be written to the media directory", e);
            }
            pictures.insert(new PictureRow(
                    one.id(), inspirationId, 0, one.format(), one.bytes().length,
                    measured == null ? null : measured.width(), measured == null ? null : measured.height(),
                    measured != null, now));
        }
    }

    @Transactional
    void remove(UserId user, UUID inspirationId, UUID pictureId) {
        Membership membership = weddings.membershipOf(user);
        PictureRow picture = pictures.find(membership.weddingId(), inspirationId, pictureId).orElseThrow(PictureService::noSuchPicture);
        pictures.delete(inspirationId, picture.id());
        forgetOnceDeleted(List.of(picture.id()));
    }

    /** Only a member of the wedding the picture belongs to gets it. */
    @Transactional(readOnly = true)
    Served open(UserId user, UUID inspirationId, UUID pictureId, Size size) {
        Membership membership = weddings.membershipOf(user);
        PictureRow picture = pictures.find(membership.weddingId(), inspirationId, pictureId).orElseThrow(PictureService::noSuchPicture);
        if (!store.holds(picture.id())) {
            // Recorded but not on the disk: a restore that brought the database back without the files.
            throw noSuchPicture();
        }
        if (size == Size.THUMBNAIL && picture.hasThumbnail()) {
            return new Served(store.thumbnail(picture.id()), PictureFormat.JPEG.contentType());
        }
        return new Served(store.original(picture.id()), picture.format().contentType());
    }

    /** Removes the files of pictures whose records are being deleted, once the deletion is certain. */
    void forgetOnceDeleted(Collection<UUID> pictureIds) {
        forget(pictureIds, TransactionSynchronization.STATUS_COMMITTED);
    }

    private void forget(Collection<UUID> pictureIds, int whenTheTransactionEnds) {
        if (pictureIds.isEmpty()) {
            return;
        }
        List<UUID> ids = List.copyOf(pictureIds);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == whenTheTransactionEnds) {
                    ids.forEach(store::delete);
                }
            }
        });
    }

    private static byte[] bytesOf(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length == 0) {
                throw ApiException.invalidField(FIELD, nameShown(file) + " is empty.");
            }
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException("An uploaded file could not be read", e);
        }
    }

    /** The file's name as the person knows it, to say which one is meant; never used for anything else. */
    private static String nameShown(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return "One of the files";
        }
        String shown = name.replaceAll("\\p{Cntrl}", "").strip();
        return "“" + (shown.length() > NAME_SHOWN ? shown.substring(0, NAME_SHOWN) + "…" : shown) + "”";
    }

    private static ApiException noSuchInspiration() {
        return new ApiException(ProblemCode.NOT_FOUND, "There is no such inspiration.");
    }

    private static ApiException noSuchPicture() {
        return new ApiException(ProblemCode.NOT_FOUND, "There is no such picture.");
    }
}
