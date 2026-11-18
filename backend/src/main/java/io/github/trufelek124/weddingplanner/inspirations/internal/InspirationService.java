package io.github.trufelek124.weddingplanner.inspirations.internal;

import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.FieldProblem;
import io.github.trufelek124.weddingplanner.api.ProblemCode;
import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationDtos.InspirationChange;
import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationDtos.InspirationFields;
import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationDtos.NewInspiration;
import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationRepository.Filter;
import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationRepository.InspirationRow;
import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationRepository.TagCount;
import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationTypes.PreviewStatus;
import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationTypes.Source;
import io.github.trufelek124.weddingplanner.inspirations.internal.PictureRepository.PictureRow;
import io.github.trufelek124.weddingplanner.planning.PlanItems;
import io.github.trufelek124.weddingplanner.wedding.Membership;
import io.github.trufelek124.weddingplanner.wedding.Weddings;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ideas the couple keeps: what may be saved, and what saving, changing and deleting do. */
@Service
class InspirationService {

    private final InspirationRepository inspirations;
    private final PictureRepository pictures;
    private final PictureService pictureFiles;
    private final PreviewWorker previews;
    private final BoardRepository boards;
    private final PlanItems planItems;
    private final Weddings weddings;
    private final Clock clock;

    InspirationService(
            InspirationRepository inspirations,
            PictureRepository pictures,
            PictureService pictureFiles,
            PreviewWorker previews,
            BoardRepository boards,
            PlanItems planItems,
            Weddings weddings,
            Clock clock) {
        this.inspirations = inspirations;
        this.pictures = pictures;
        this.pictureFiles = pictureFiles;
        this.previews = previews;
        this.boards = boards;
        this.planItems = planItems;
        this.weddings = weddings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    List<InspirationView> list(UserId user, UUID boardId, Boolean withoutBoard, String tag, UUID planItemId) {
        Membership membership = weddings.membershipOf(user);
        String asked = tag == null || tag.isBlank() ? null : tagAsKept(tag);
        List<InspirationRow> found = inspirations.list(membership.weddingId(), new Filter(boardId, withoutBoard, asked, planItemId));
        Map<UUID, List<PictureRow>> theirPictures = pictures.of(found.stream().map(InspirationRow::id).toList()).stream()
                .collect(Collectors.groupingBy(PictureRow::inspirationId));
        return found.stream().map(row -> new InspirationView(row, theirPictures.getOrDefault(row.id(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    InspirationView get(UserId user, UUID id) {
        return shown(existing(weddings.membershipOf(user), id));
    }

    @Transactional(readOnly = true)
    List<TagCount> tags(UserId user) {
        return inspirations.tags(weddings.membershipOf(user).weddingId());
    }

    @Transactional
    InspirationView add(UserId user, NewInspiration request) {
        Membership membership = weddings.membershipOf(user);
        Optional<URI> link = checked(membership, request);
        Instant now = clock.instant();
        InspirationRow inspiration = new InspirationRow(
                UUID.randomUUID(), link.map(URI::toString).orElse(null), link.map(Links::sourceOf).orElse(Source.PHOTO),
                request.title().strip(), request.note().strip(), tagsAsKept(request.tags()), request.boardId(),
                request.planItemId(), membership.memberId(), link.isPresent() ? PreviewStatus.PENDING : PreviewStatus.NONE, "",
                0, now, now);
        inspirations.insert(membership.weddingId(), inspiration);
        if (link.isPresent()) {
            previews.wakeOnceSaved();
        }
        return shown(existing(membership, inspiration.id()));
    }

    @Transactional
    InspirationView change(UserId user, UUID id, InspirationChange request) {
        Membership membership = weddings.membershipOf(user);
        InspirationRow before = existing(membership, id);
        Optional<URI> link = checked(membership, request);
        Instant now = clock.instant();
        InspirationRow after = new InspirationRow(
                id, link.map(URI::toString).orElse(null), link.map(Links::sourceOf).orElse(Source.PHOTO),
                request.title().strip(), request.note().strip(), tagsAsKept(request.tags()), request.boardId(),
                request.planItemId(), before.addedBy(), before.previewStatus(), before.previewTitle(), before.version(),
                before.createdAt(), now);
        if (!inspirations.update(membership.weddingId(), after, request.version(), now)) {
            throw new ApiException(
                    ProblemCode.CHANGED_MEANWHILE, "Someone else changed this in the meantime. Load it again, then make your change.");
        }
        if (!Objects.equals(before.url(), after.url())) {
            // Another link, or none any more: what was fetched for the old one goes, and the new one gets its turn.
            pictureFiles.forgetOnceDeleted(pictures.deletePreviews(id));
            inspirations.startPreviewAgain(id, link.isPresent() ? PreviewStatus.PENDING : PreviewStatus.NONE, now);
            if (link.isPresent()) {
                previews.wakeOnceSaved();
            }
        }
        return shown(existing(membership, id));
    }

    /** Its pictures go with it, from the disk too. */
    @Transactional
    void delete(UserId user, UUID id) {
        Membership membership = weddings.membershipOf(user);
        existing(membership, id);
        List<UUID> itsPictures = pictures.idsOfInspiration(id);
        inspirations.delete(membership.weddingId(), id);
        pictureFiles.forgetOnceDeleted(itsPictures);
    }

    /**
     * Checks what the request points at.
     *
     * @return the link, when there is one
     */
    private Optional<URI> checked(Membership membership, InspirationFields request) {
        List<FieldProblem> problems = new ArrayList<>();
        Optional<URI> link = Optional.empty();
        if (request.url() != null && !request.url().isBlank()) {
            link = Links.parse(request.url());
            if (link.isEmpty()) {
                problems.add(new FieldProblem("url", "Enter a link that starts with http:// or https://."));
            }
        }
        if (request.boardId() != null && !boards.exists(membership.weddingId(), request.boardId())) {
            problems.add(new FieldProblem("boardId", "Choose one of your boards."));
        }
        if (request.planItemId() != null && !planItems.exists(membership.weddingId(), request.planItemId())) {
            problems.add(new FieldProblem("planItemId", "Choose one of your plan items."));
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ProblemCode.BAD_REQUEST, "The request is not valid.", problems);
        }
        return link;
    }

    private InspirationView shown(InspirationRow inspiration) {
        return new InspirationView(inspiration, pictures.of(List.of(inspiration.id())));
    }

    private InspirationRow existing(Membership membership, UUID id) {
        return inspirations.find(membership.weddingId(), id).orElseThrow(InspirationService::noSuchInspiration);
    }

    /** Each tag once, as it is kept, in the order given. */
    private static List<String> tagsAsKept(List<String> tags) {
        Set<String> kept = new LinkedHashSet<>();
        for (String tag : tags) {
            String one = tagAsKept(tag);
            if (!one.isEmpty()) {
                kept.add(one);
            }
        }
        return List.copyOf(kept);
    }

    /** "  #Boho  Chic " and "boho chic" are one tag: lower case, single spaces, no leading #. */
    static String tagAsKept(String tag) {
        return tag.strip().replaceFirst("^#+", "").strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static ApiException noSuchInspiration() {
        return new ApiException(ProblemCode.NOT_FOUND, "There is no such inspiration.");
    }
}
