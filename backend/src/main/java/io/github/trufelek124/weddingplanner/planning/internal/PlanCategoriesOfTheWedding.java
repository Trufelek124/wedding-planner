package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.planning.PlanCategories;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class PlanCategoriesOfTheWedding implements PlanCategories {

    private final CategoryRepository categories;

    PlanCategoriesOfTheWedding(CategoryRepository categories) {
        this.categories = categories;
    }

    @Override
    public Optional<UUID> named(UUID weddingId, String name) {
        return name == null || name.isBlank() ? Optional.empty() : categories.findByName(weddingId, name);
    }
}
