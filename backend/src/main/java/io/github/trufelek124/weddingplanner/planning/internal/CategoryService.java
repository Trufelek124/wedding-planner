package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.ProblemCode;
import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.planning.internal.CategoryRepository.CategoryRow;
import io.github.trufelek124.weddingplanner.wedding.Weddings;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The headings entries are filed under. The couple edits the list; a starter list is offered once. */
@Service
class CategoryService {

    static final List<String> STARTER = List.of(
            "Venue", "Food and drink", "Attire", "Photo and video", "Music", "Flowers and decor", "Invitations",
            "Formalities", "Guests", "Transport", "Honeymoon", "Other");

    private static final String NAME_TAKEN = "There is a category of that name already.";

    private final CategoryRepository categories;
    private final Weddings weddings;
    private final Clock clock;

    CategoryService(CategoryRepository categories, Weddings weddings, Clock clock) {
        this.categories = categories;
        this.weddings = weddings;
        this.clock = clock;
    }

    @Transactional
    List<CategoryRow> list(UserId user) {
        UUID weddingId = weddings.membershipOf(user).weddingId();
        if (categories.markOffered(weddingId, clock.instant())) {
            STARTER.forEach(name -> categories.insert(weddingId, UUID.randomUUID(), name));
        }
        return categories.list(weddingId);
    }

    @Transactional
    CategoryRow add(UserId user, String name) {
        UUID weddingId = weddings.membershipOf(user).weddingId();
        UUID id = UUID.randomUUID();
        if (!categories.insert(weddingId, id, name.strip())) {
            throw ApiException.invalidField("name", NAME_TAKEN);
        }
        return categories.list(weddingId).stream().filter(category -> category.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    CategoryRow rename(UserId user, UUID id, String name) {
        UUID weddingId = weddings.membershipOf(user).weddingId();
        int renamed = categories.rename(weddingId, id, name.strip());
        if (renamed < 0) {
            throw ApiException.invalidField("name", NAME_TAKEN);
        }
        if (renamed == 0) {
            throw noSuchCategory();
        }
        return categories.list(weddingId).stream().filter(category -> category.id().equals(id)).findFirst().orElseThrow();
    }

    /** Entries filed under the category stay, without a category. */
    @Transactional
    void delete(UserId user, UUID id) {
        if (!categories.delete(weddings.membershipOf(user).weddingId(), id)) {
            throw noSuchCategory();
        }
    }

    private static ApiException noSuchCategory() {
        return new ApiException(ProblemCode.NOT_FOUND, "There is no such category.");
    }
}
